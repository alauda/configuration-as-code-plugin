package io.jenkins.plugins.casc.yaml;

import io.jenkins.plugins.casc.ConfiguratorException;
import io.jenkins.plugins.casc.snakeyaml.nodes.Node;

/**
 * YAML merge strategy between multiple files
 */
public interface MergeStrategy {
    String DEFAULT_STRATEGY = "irreconcilable";

    /**
     * Merge two nodes which come from two YAML files
     * @param firstNode the first node of a node list
     * @param secondNode the second node of a node list
     * @param source is the source of node
     * @return a merged node object
     * @throws ConfiguratorException if the process gets some errors
     */
    Node merge(Node firstNode, Node secondNode, String source) throws ConfiguratorException;

    /**
     * Name of the merge strategy which must be unique.
     * @return name of the merge strategy
     */
    String getName();
}
