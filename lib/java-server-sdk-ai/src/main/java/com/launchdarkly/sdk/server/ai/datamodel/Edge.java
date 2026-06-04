package com.launchdarkly.sdk.server.ai.datamodel;

import com.launchdarkly.sdk.LDValue;

import java.util.Objects;

/**
 * An edge in an {@link AIAgentGraphConfig}, describing a directed relationship between two agent
 * nodes and any handoff options associated with it.
 */
public final class Edge {
  private final String key;
  private final String sourceConfig;
  private final String targetConfig;
  private final LDValue handoff;

  /**
   * Creates an edge.
   *
   * @param key the edge key
   * @param sourceConfig the key of the source agent node
   * @param targetConfig the key of the target agent node
   * @param handoff handoff options for this relationship, or {@link LDValue#ofNull()}
   */
  public Edge(String key, String sourceConfig, String targetConfig, LDValue handoff) {
    this.key = key;
    this.sourceConfig = sourceConfig;
    this.targetConfig = targetConfig;
    this.handoff = handoff == null ? LDValue.ofNull() : handoff;
  }

  /**
   * Returns the edge key.
   *
   * @return the key
   */
  public String getKey() {
    return key;
  }

  /**
   * Returns the key of the source agent node.
   *
   * @return the source config key
   */
  public String getSourceConfig() {
    return sourceConfig;
  }

  /**
   * Returns the key of the target agent node.
   *
   * @return the target config key
   */
  public String getTargetConfig() {
    return targetConfig;
  }

  /**
   * Returns the handoff options for this relationship.
   *
   * @return the handoff options, or {@link LDValue#ofNull()} if none
   */
  public LDValue getHandoff() {
    return handoff;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Edge)) {
      return false;
    }
    Edge other = (Edge) o;
    return Objects.equals(key, other.key)
        && Objects.equals(sourceConfig, other.sourceConfig)
        && Objects.equals(targetConfig, other.targetConfig)
        && Objects.equals(handoff, other.handoff);
  }

  @Override
  public int hashCode() {
    return Objects.hash(key, sourceConfig, targetConfig, handoff);
  }

  @Override
  public String toString() {
    return "Edge{key=" + key + ", sourceConfig=" + sourceConfig + ", targetConfig=" + targetConfig + "}";
  }
}
