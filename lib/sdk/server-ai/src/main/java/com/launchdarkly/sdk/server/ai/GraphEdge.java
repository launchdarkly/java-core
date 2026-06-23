package com.launchdarkly.sdk.server.ai;

import com.launchdarkly.sdk.LDValue;

import java.util.Collections;
import java.util.Map;

/**
 * An edge in an agent graph, representing a directed connection from one node to a target node.
 * <p>
 * Each edge carries the key of the target {@link AgentGraphNode} and an optional handoff map of
 * arbitrary data that may be passed when transitioning to the target node. Instances are immutable.
 */
public final class GraphEdge {
  private final String key;
  private final Map<String, LDValue> handoff;

  public GraphEdge(String key, Map<String, LDValue> handoff) {
    this.key = key;
    this.handoff = handoff;
  }

  /**
   * Returns the key of the target node that this edge points to.
   *
   * @return the target node key; never {@code null}
   */
  public String getKey() {
    return key;
  }

  /**
   * Returns the handoff options for this edge.
   * <p>
   * The handoff is an optional map of arbitrary values that may be passed when transitioning
   * to the target node. If no handoff was defined for this edge, returns {@code null}.
   *
   * @return an unmodifiable map of handoff values, or {@code null} if absent
   */
  public Map<String, LDValue> getHandoff() {
    return handoff;
  }
}
