package io.jenkins.plugins.casc.auto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;

public class MergeTest {

    public static void main(String[] args) {
        File userConfig = new File("user.yaml");
        File systemConfig = new File("sys.yaml");

        YamlMapper mapper = new YamlMapper();
        try {
            JsonNode merged = merge(mapper.read(userConfig), mapper.read(systemConfig));
            if(merged != null && !merged.isNull()) {
                try (OutputStream userFileOutput = new FileOutputStream(userConfig)) {
                    mapper.write(new YAMLFactory().createGenerator(userFileOutput), merged);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static JsonNode merge(JsonNode mainNode, JsonNode updateNode) {
        if (mainNode == null || updateNode == null) {
            return mainNode;
        }
        Iterator<String> fieldNames = updateNode.fieldNames();

        while (fieldNames.hasNext()) {
            String updatedFieldName = fieldNames.next();
            JsonNode valueToBeUpdated = mainNode.get(updatedFieldName);
            JsonNode updatedValue = updateNode.get(updatedFieldName);

            if (valueToBeUpdated != null && valueToBeUpdated.isArray() &&
                updatedValue.isArray()) {
                ArrayNode updatedArrayNode = (ArrayNode)updatedValue;
                ArrayNode arrayNodeToBeUpdated = (ArrayNode)valueToBeUpdated;
                for(int i = 0; updatedArrayNode.has(i);++i) {
                    if(arrayNodeToBeUpdated.has(i)) {
                        JsonNode mergedNode = merge(arrayNodeToBeUpdated.get(i), updatedArrayNode.get(i));
                        arrayNodeToBeUpdated.set(i, mergedNode);
                    } else {
                        arrayNodeToBeUpdated.add(updatedArrayNode.get(i));
                    }
                }
                // if the Node is an @ObjectNode
            } else if (valueToBeUpdated != null && valueToBeUpdated.isObject() && updatedValue != null && !updatedValue.isNull()) {
                merge(valueToBeUpdated, updatedValue);
            } else {
                if (updatedValue != null && !updatedValue.isNull()) {
                    ((ObjectNode) mainNode).replace(updatedFieldName, updatedValue);
                }
                else {
                    ((ObjectNode) mainNode).remove(updatedFieldName);
                }
            }
        }
        if (updateNode instanceof TextNode) {
            return updateNode;
        }
        return mainNode;
    }
}
