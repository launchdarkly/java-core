package com.launchdarkly.sdk.server.ai;

import java.util.Collections;
import java.util.List;

/**
 * A node in an {@link AgentGraphDefinition}, wrapping a single agent config and its outgoing
 * edges to other nodes.
 * <p>
 * Nodes are retrieved from a graph definition via {@link AgentGraphDefinition#getNode(String)},
 * {@link AgentGraphDefinition#rootNode()}, etc. Instances are immutable.
 */
public final class AgentGraphNode {
  private final String key;
  private final AIAgentConfig config;
  private final List<GraphEdge> edges;

  AgentGraphNode(String key, AIAgentConfig config, List<GraphEdge> edges) {
    this.key = key;
    this.config = config;
    this.edges = edges == null ? Collections.<GraphEdge>emptyList() : edges;
  }

  /**
   * Returns the AI Config key identifying this node.
   *
   * @return the node key; never {@code null}
   */
  public String getKey() {
    return key;
  }

  /**
   * Returns the retrieved agent config for this node.
   *
   * @return the agent config; never {@code null}
   */
  public AIAgentConfig getConfig() {
    return config;
  }

  /**
   * Returns the outgoing edges from this node to other nodes.
   *
   * @return an unmodifiable list of outgoing edges; never {@code null} but may be empty
   */
  public List<GraphEdge> getEdges() {
    return edges;
  }

  /**
   * Returns {@code true} if this node has no outgoing edges.
   *
   * @return {@code true} if terminal (no edges), {@code false} otherwise
   */
  public boolean isTerminal() {
    return edges.isEmpty();
  }
}
