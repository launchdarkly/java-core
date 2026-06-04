package com.launchdarkly.sdk.server.ai.datamodel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Configuration describing an agentic graph flow composed of multiple interconnected
 * {@link AIAgentConfig} nodes.
 */
public final class AIAgentGraphConfig {
  private final String key;
  private final String rootConfigKey;
  private final List<Edge> edges;
  private final boolean enabled;

  /**
   * Creates an agent graph configuration.
   *
   * @param key the graph configuration key
   * @param rootConfigKey the key of the root agent node
   * @param edges the edges defining relationships between agent nodes
   * @param enabled whether the graph is enabled
   */
  public AIAgentGraphConfig(String key, String rootConfigKey, List<Edge> edges, boolean enabled) {
    this.key = key;
    this.rootConfigKey = rootConfigKey;
    this.edges = Collections.unmodifiableList(new ArrayList<>(edges));
    this.enabled = enabled;
  }

  /**
   * Returns the graph configuration key.
   *
   * @return the key
   */
  public String getKey() {
    return key;
  }

  /**
   * Returns the key of the root agent node.
   *
   * @return the root config key
   */
  public String getRootConfigKey() {
    return rootConfigKey;
  }

  /**
   * Returns the edges defining relationships between agent nodes.
   *
   * @return an unmodifiable list of edges
   */
  public List<Edge> getEdges() {
    return edges;
  }

  /**
   * Returns whether the graph is enabled.
   *
   * @return {@code true} if enabled
   */
  public boolean isEnabled() {
    return enabled;
  }
}
