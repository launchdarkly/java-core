package com.launchdarkly.sdk.server.ai.internal;

import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.LDValueType;
import com.launchdarkly.sdk.server.ai.GraphEdge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The parsed, strongly-typed representation of an agent graph flag variation's JSON protocol.
 * <p>
 * Mirrors the wire structure: the {@code _ldMeta} block (enabled / variationKey / version) plus
 * the {@code root} config key and the {@code edges} adjacency map. Produced by {@link #parse} and
 * consumed when assembling {@link com.launchdarkly.sdk.server.ai.AgentGraphDefinition}.
 * <p>
 * Parsing is intentionally defensive: malformed, missing, or wrong-typed fields never raise an
 * exception. When {@code _ldMeta.enabled} is absent the default is {@code true}; when
 * {@code _ldMeta.version} is absent the default is {@code 1}.
 * <p>
 * This class is an internal implementation detail and is not part of the supported API.
 */
public final class AgentGraphFlagValue {
  private static final int DEFAULT_VERSION = 1;

  private final String root;
  private final Map<String, List<GraphEdge>> edges;
  private final String variationKey;
  private final int version;
  private final boolean enabled;

  private AgentGraphFlagValue(Builder b) {
    this.root = b.root;
    this.edges = b.edges == null
        ? Collections.<String, List<GraphEdge>>emptyMap()
        : Collections.unmodifiableMap(b.edges);
    this.variationKey = b.variationKey;
    this.version = b.version;
    this.enabled = b.enabled;
  }

  /**
   * Returns a disabled flag value with empty root and no edges. Used when the raw flag value is
   * not a JSON object or when validation fails.
   *
   * @return a disabled {@link AgentGraphFlagValue}
   */
  public static AgentGraphFlagValue disabled() {
    return new Builder().enabled(false).build();
  }

  /**
   * Parses a raw {@link LDValue} flag variation into a strongly-typed {@link AgentGraphFlagValue}.
   * <p>
   * Returns {@link #disabled()} when {@code value} is not a JSON object.
   *
   * @param value the raw flag value; may be {@code null} or any JSON type
   * @return the parsed representation; never {@code null}
   */
  public static AgentGraphFlagValue parse(LDValue value) {
    if (value == null || value.getType() != LDValueType.OBJECT) {
      return disabled();
    }

    Builder builder = new Builder(); // defaults: enabled=true, version=1

    LDValue meta = value.get("_ldMeta");
    if (meta != null && meta.getType() == LDValueType.OBJECT) {
      LDValue enabledVal = meta.get("enabled");
      if (enabledVal.getType() == LDValueType.BOOLEAN) {
        builder.enabled(enabledVal.booleanValue());
      }
      LDValue variationKeyVal = meta.get("variationKey");
      if (variationKeyVal.getType() == LDValueType.STRING) {
        builder.variationKey(variationKeyVal.stringValue());
      }
      LDValue versionVal = meta.get("version");
      if (versionVal.getType() == LDValueType.NUMBER) {
        builder.version(versionVal.intValue());
      }
    }

    LDValue rootVal = value.get("root");
    if (rootVal.getType() == LDValueType.STRING) {
      builder.root(rootVal.stringValue());
    }

    LDValue edgesVal = value.get("edges");
    if (edgesVal.getType() == LDValueType.OBJECT) {
      Map<String, List<GraphEdge>> edges = new LinkedHashMap<>();
      for (String sourceKey : edgesVal.keys()) {
        LDValue edgeArray = edgesVal.get(sourceKey);
        if (edgeArray.getType() != LDValueType.ARRAY) {
          continue;
        }
        List<GraphEdge> edgeList = new ArrayList<>();
        for (LDValue edgeObj : edgeArray.values()) {
          if (edgeObj == null || edgeObj.getType() != LDValueType.OBJECT) {
            continue;
          }
          LDValue keyVal = edgeObj.get("key");
          if (keyVal.getType() != LDValueType.STRING) {
            continue;
          }
          String targetKey = keyVal.stringValue();
          Map<String, LDValue> handoff = parseHandoff(edgeObj.get("handoff"));
          edgeList.add(new GraphEdge(targetKey, handoff));
        }
        edges.put(sourceKey, Collections.unmodifiableList(edgeList));
      }
      builder.edges(edges);
    }

    return builder.build();
  }

  private static Map<String, LDValue> parseHandoff(LDValue handoff) {
    if (handoff == null || handoff.getType() != LDValueType.OBJECT) {
      return null;
    }
    Map<String, LDValue> result = new LinkedHashMap<>();
    for (String key : handoff.keys()) {
      result.put(key, handoff.get(key));
    }
    return result.isEmpty() ? null : Collections.unmodifiableMap(result);
  }

  /**
   * Returns the root node's AI Config key.
   *
   * @return the root key; never {@code null}, but may be empty when not specified
   */
  public String getRoot() {
    return root;
  }

  /**
   * Returns the adjacency map of outgoing edges keyed by source node config key.
   *
   * @return an unmodifiable map; never {@code null} but may be empty
   */
  public Map<String, List<GraphEdge>> getEdges() {
    return edges;
  }

  /**
   * Returns the {@code _ldMeta.variationKey}.
   *
   * @return the variation key, or {@code null} if absent
   */
  public String getVariationKey() {
    return variationKey;
  }

  /**
   * Returns the {@code _ldMeta.version}, defaulting to 1 when absent.
   *
   * @return the version; always &gt;= 1
   */
  public int getVersion() {
    return version;
  }

  /**
   * Returns {@code true} if the graph is enabled. Defaults to {@code true} when
   * {@code _ldMeta.enabled} is absent.
   *
   * @return whether the graph is enabled
   */
  public boolean isEnabled() {
    return enabled;
  }

  static Builder builder() {
    return new Builder();
  }

  static final class Builder {
    private String root = "";
    private Map<String, List<GraphEdge>> edges;
    private String variationKey;
    private int version = DEFAULT_VERSION;
    private boolean enabled = true;

    private Builder() {
    }

    Builder root(String v) {
      this.root = v;
      return this;
    }

    Builder edges(Map<String, List<GraphEdge>> v) {
      this.edges = v;
      return this;
    }

    Builder variationKey(String v) {
      this.variationKey = v;
      return this;
    }

    Builder version(int v) {
      this.version = v;
      return this;
    }

    Builder enabled(boolean v) {
      this.enabled = v;
      return this;
    }

    AgentGraphFlagValue build() {
      return new AgentGraphFlagValue(this);
    }
  }
}
