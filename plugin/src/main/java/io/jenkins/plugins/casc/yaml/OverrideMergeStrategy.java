package io.jenkins.plugins.casc.yaml;

import hudson.Extension;
import io.jenkins.plugins.casc.ConfigurationAsCode;
import io.jenkins.plugins.casc.ConfiguratorException;
import io.jenkins.plugins.casc.model.CNode;
import io.jenkins.plugins.casc.model.Mapping;
import io.jenkins.plugins.casc.model.Scalar;
import io.jenkins.plugins.casc.model.Scalar.Format;
import io.jenkins.plugins.casc.model.Sequence;
import io.jenkins.plugins.casc.snakeyaml.DumperOptions;
import io.jenkins.plugins.casc.snakeyaml.nodes.MappingNode;
import io.jenkins.plugins.casc.snakeyaml.nodes.Node;
import io.jenkins.plugins.casc.snakeyaml.nodes.NodeTuple;
import io.jenkins.plugins.casc.snakeyaml.nodes.ScalarNode;
import io.jenkins.plugins.casc.snakeyaml.nodes.SequenceNode;
import io.jenkins.plugins.casc.snakeyaml.nodes.Tag;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static io.jenkins.plugins.casc.snakeyaml.DumperOptions.FlowStyle.BLOCK;
import static io.jenkins.plugins.casc.snakeyaml.DumperOptions.ScalarStyle.DOUBLE_QUOTED;
import static io.jenkins.plugins.casc.snakeyaml.DumperOptions.ScalarStyle.LITERAL;
import static io.jenkins.plugins.casc.snakeyaml.DumperOptions.ScalarStyle.PLAIN;

@Extension
public class OverrideMergeStrategy implements MergeStrategy {

    private Node getKeyValue(MappingNode map, String key) {
        NodeTuple node = map.getValue().stream()
            .filter(item -> ((ScalarNode) item.getKeyNode()).getValue().equals(key)).findFirst()
            .orElse(null);
        if (node != null) {
            return node.getValueNode();
        }
        return null;
    }


    private Node findNode(MappingNode seq2, String key) {
        NodeTuple nodeItem = seq2.getValue().stream().filter(item -> {
            return ((ScalarNode) item.getKeyNode()).getValue().equals(key);
        }).findFirst().orElse(null);
        if (nodeItem != null) {
            return nodeItem.getValueNode();
        }
        return null;
    }

    private MappingNode findMap(SequenceNode seq2, String key, String keyValue) {
        Node mappingNode = seq2.getValue().stream().filter(item -> {
            if (item instanceof MappingNode) {
                NodeTuple node = ((MappingNode) item).getValue().stream()
                    .filter(mapItem -> ((ScalarNode) mapItem.getKeyNode()).getValue().equals(key))
                    .findFirst()
                    .orElse(null);
                if (node != null && node.getKeyNode() instanceof ScalarNode) {
                    return ((ScalarNode) node.getKeyNode()).getValue().equals(keyValue);
                }
            }
            return false;
        }).findFirst().orElse(null);
        if (mappingNode instanceof MappingNode) {
            return (MappingNode) mappingNode;
        }
        return null;
    }

    public Node toYaml(CNode config) throws ConfiguratorException {

        if (config == null) return null;

        switch (config.getType()) {
            case MAPPING:
                final Mapping mapping = config.asMapping();
                final List<NodeTuple> tuples = new ArrayList<>();
                final List<Map.Entry<String, CNode>> entries = new ArrayList<>(mapping.entrySet());
                entries.sort(Comparator.comparing(Map.Entry::getKey));
                for (Map.Entry<String, CNode> entry : entries) {
                    final Node valueNode = toYaml(entry.getValue());
                    if (valueNode == null) continue;
                    tuples.add(new NodeTuple(
                        new ScalarNode(Tag.STR, entry.getKey(), null, null, PLAIN),
                        valueNode));

                }
                if (tuples.isEmpty()) return null;

                return new MappingNode(Tag.MAP, tuples, BLOCK);

            case SEQUENCE:
                final Sequence sequence = config.asSequence();
                List<Node> nodes = new ArrayList<>();
                for (CNode cNode : sequence) {
                    final Node valueNode = toYaml(cNode);
                    if (valueNode == null) continue;
                    nodes.add(valueNode);
                }
                if (nodes.isEmpty()) return null;
                return new SequenceNode(Tag.SEQ, nodes, BLOCK);

            case SCALAR:
            default:
                final Scalar scalar = config.asScalar();
                final String value = scalar.getValue();
                if (value == null || value.length() == 0) return null;

                final DumperOptions.ScalarStyle style;
                if (scalar.getFormat().equals(Format.MULTILINESTRING) && !scalar.isRaw()) {
                    style = LITERAL;
                } else if (scalar.isRaw()) {
                    style = PLAIN;
                } else {
                    style = DOUBLE_QUOTED;
                }

                return new ScalarNode(getTag(scalar.getFormat()), value, null, null, style);
        }
    }

    private Tag getTag(Scalar.Format format) {
        switch (format) {
            case NUMBER:
                return Tag.INT;
            case FLOATING:
                return Tag.FLOAT;
            case BOOLEAN:
                return Tag.BOOL;
            case STRING:
            case MULTILINESTRING:
            default:
                return Tag.STR;
        }
    }

    private Node mergeByCLI(Node root, Node node, String source) throws IOException {
        File rootFile = File.createTempFile("root", "yaml");
        File nodeFile = File.createTempFile("node", "yaml");

        try{
            Writer rootWriter = new OutputStreamWriter(new FileOutputStream(rootFile));
            Writer nodeWriter = new OutputStreamWriter(new FileOutputStream(nodeFile));

            ConfigurationAsCode.serializeYamlNode(root, rootWriter);
            ConfigurationAsCode.serializeYamlNode(node, nodeWriter);

            Runtime.getRuntime().exec(String.format("yq merge %s %s -x -i",
                rootFile.getAbsolutePath(), nodeFile.getAbsolutePath()));

            YamlSource<InputStream> yamlSource = YamlSource.of(new FileInputStream(rootFile));

            return YamlUtils.read(yamlSource);
        } finally {
            rootFile.delete();
            nodeFile.delete();
        }
    }

    @Override
    public Node merge(Node root, Node node, String source) throws ConfiguratorException {
        try {
            return mergeByCLI(root, node, source);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;

//        if (root.getNodeId() != node.getNodeId()) {
//            // means one of those yaml file doesn't conform to JCasC schema
//            throw new ConfiguratorException(
//                String.format("Found incompatible configuration elements %s %s", source, node.getStartMark()));
//        }
//
//        switch (root.getNodeId()) {
//            case sequence:
//                SequenceNode seq = (SequenceNode) root;
//                SequenceNode seq2 = (SequenceNode) node;
//
//                boolean isScalar = false;
//                boolean isMapping = false;
//                List<Node> seqValues = seq.getValue();
//                if (seqValues.size() > 0) {
//                    isScalar = (seqValues.get(0).getNodeId() == NodeId.scalar);
//                    isMapping = (seqValues.get(0).getNodeId() == NodeId.mapping);
//                } else if (seq2.getValue().size() > 0) {
//                    isScalar = (seq2.getValue().get(0).getNodeId() == NodeId.scalar);
//                    isMapping = (seq2.getValue().get(0).getNodeId() == NodeId.mapping);
//                }
//
//                if (isScalar) {
//                    seq.getValue().addAll(seq2.getValue());
//                } else if (isMapping) {
//                    for(Node nodeItem : seqValues) {
//                        MappingNode map = (MappingNode) nodeItem;
//
//                        Node keyNode = getKeyValue(map, "name");
//                        if (keyNode instanceof ScalarNode) {
//                            MappingNode rightMap = findMap(seq2, "name",
//                                ((ScalarNode) keyNode).getValue());
//                            if (rightMap != null) {
//                                merge(map, rightMap, "");
//
//                                seq2.getValue().remove(rightMap);
//                                break;
//                            }
//                        } else {
//                            keyNode = getKeyValue(map, "id");
//                            if (keyNode instanceof ScalarNode) {
//                                MappingNode rightMap = findMap(seq2, "id",
//                                    ((ScalarNode) keyNode).getValue());
//                                if (rightMap != null) {
//                                    merge(map, rightMap, "");
//
//                                    seq2.getValue().remove(rightMap);
//                                    break;
//                                }
//                            }
//                        }
//
//                        // need to take look at deeply
//
//                        for(Node right : seq2.getValue()) {
//                            // it should be same with the left one
//                            if (!(right instanceof  MappingNode)) {
//                                break;
//                            }
//
//                            merge(map, right, "");
//                        }
//                    }
//
//                    if(seq2.getValue().size() > 0) {
//                        seq.getValue().addAll(seq2.getValue());
//                    }
//                }
//                return;
//            case mapping:
//                MappingNode leftMap = (MappingNode) root;
//                MappingNode rightMap = (MappingNode) node;
//                // merge common entries
//                Iterator<NodeTuple> rightIt = rightMap.getValue().iterator();
//                while (rightIt.hasNext()) {
//                    NodeTuple rightTuple = rightIt.next();
//                    for (NodeTuple lefttuple : leftMap.getValue()) {
//
//                        final Node leftKey = lefttuple.getKeyNode();
//                        final Node rightkey = rightTuple.getKeyNode();
//
//
//                        if(lefttuple.getValueNode().getNodeId() == NodeId.scalar) {
//                            Node scalarNode = findNode(leftMap, ((ScalarNode) leftKey).getValue());
//                            if (scalarNode != null){
//                                rightMap.getValue().remove(rightTuple);
//                                leftMap.getValue().remove(lefttuple);
//
//                                rightIt = rightMap.getValue().iterator();
//                                break;
//                            }
//                        } else if(lefttuple.getValueNode().getNodeId() == NodeId.sequence) {
//                            Node scalarNode = findNode(leftMap, ((ScalarNode) leftKey).getValue());
//                            if (scalarNode instanceof SequenceNode) {
//                                merge(scalarNode, rightTuple.getValueNode(), "");
//                                rightMap.getValue().remove(rightTuple);
//                                leftMap.getValue().remove(lefttuple);
//
//                                rightIt = rightMap.getValue().iterator();
//                                break;
//                            }
//                        } else if(lefttuple.getValueNode().getNodeId() == NodeId.mapping) {
//                            Node scalarNode = findNode(leftMap, ((ScalarNode) leftKey).getValue());
//                            if (scalarNode instanceof MappingNode) {
//                                merge(scalarNode, rightTuple.getValueNode(), "");
//                                rightMap.getValue().remove(rightTuple);
//                                leftMap.getValue().remove(lefttuple);
//
//                                rightIt = rightMap.getValue().iterator();
//                                break;
//                            }
//                        }
//
////                        if (leftKey.getNodeId() == NodeId.scalar) {
////                            // We dont support merge for more complex cases (yet)
////
////                            if (((ScalarNode) leftKey).getValue()
////                                .equals(((ScalarNode) rightkey).getValue())) {
////                                merge(lefttuple.getValueNode(), rightTuple.getValueNode(), source);
////                            }
////                        } else {
////                            throw new ConfiguratorException(
////                                String.format("Found unmergeable configuration keys %s %s)", source,
////                                    node.getEndMark()));
////                        }
//                    }
//                }
//                // .. and add others
////                leftMap.getValue().addAll(rightMap.getValue());
//            default:
//                // ignore
//        }
    }

    @Override
    public String getName() {
        return "override";
    }
}
