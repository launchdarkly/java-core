package com.launchdarkly.sdk.server.ai.datamodel;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * A single request entry passed to {@code LDAIClient.agentConfigs}, combining an agent key with its
 * own default configuration and interpolation variables.
 */
public final class AIAgentConfigRequest {
  private final String key;
  private final AIAgentConfigDefault defaultValue;
  private final Map<String, Object> variables;

  /**
   * Creates a request for an agent with no default and no variables.
   *
   * @param key the agent configuration key
   */
  public AIAgentConfigRequest(String key) {
    this(key, null, null);
  }

  /**
   * Creates a request for an agent.
   *
   * @param key the agent configuration key
   * @param defaultValue the default value to use when no flag variation is available, or {@code null}
   * @param variables the variables for instruction interpolation, or {@code null}
   */
  public AIAgentConfigRequest(String key, AIAgentConfigDefault defaultValue, Map<String, Object> variables) {
    this.key = key;
    this.defaultValue = defaultValue;
    this.variables = variables == null
        ? null
        : Collections.unmodifiableMap(new HashMap<>(variables));
  }

  /**
   * Returns the agent configuration key.
   *
   * @return the key
   */
  public String getKey() {
    return key;
  }

  /**
   * Returns the default value for this agent.
   *
   * @return the default, or {@code null} if none was provided
   */
  public AIAgentConfigDefault getDefaultValue() {
    return defaultValue;
  }

  /**
   * Returns the interpolation variables for this agent.
   *
   * @return an unmodifiable map of variables, or {@code null} if none were provided
   */
  public Map<String, Object> getVariables() {
    return variables;
  }
}
