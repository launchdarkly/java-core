package com.launchdarkly.sdk.server.ai;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A single agent request passed to {@link LDAIClient#agentConfigs}, pairing an agent key with its
 * own default and interpolation variables.
 * <p>
 * Build instances with {@link #builder(String)}. Instances are immutable.
 */
public final class AIAgentConfigRequest {
  private final String key;
  private final AIAgentConfigDefault defaultValue;
  private final Map<String, Object> variables;

  private AIAgentConfigRequest(Builder builder) {
    this.key = builder.key;
    this.defaultValue = builder.defaultValue;
    this.variables = builder.variables == null
        ? null : Collections.unmodifiableMap(new HashMap<>(builder.variables));
  }

  /**
   * Returns the agent key to retrieve.
   *
   * @return the agent key, never {@code null}
   */
  public String getKey() {
    return key;
  }

  /**
   * Returns the default for this agent.
   *
   * @return the default, or {@code null} if a disabled default should be used
   */
  public AIAgentConfigDefault getDefaultValue() {
    return defaultValue;
  }

  /**
   * Returns the interpolation variables for this agent's instructions.
   *
   * @return an unmodifiable map of variables, or {@code null} if none were specified
   */
  public Map<String, Object> getVariables() {
    return variables;
  }

  /**
   * Creates a new builder for a request with the given agent key.
   *
   * @param key the agent key; must not be {@code null}
   * @return a new {@link Builder}
   * @throws NullPointerException if {@code key} is {@code null}
   */
  public static Builder builder(String key) {
    return new Builder(Objects.requireNonNull(key, "key"));
  }

  /**
   * Builder for {@link AIAgentConfigRequest}.
   */
  public static final class Builder {
    private final String key;
    private AIAgentConfigDefault defaultValue;
    private Map<String, Object> variables;

    private Builder(String key) {
      this.key = key;
    }

    /**
     * Sets the default for this agent.
     *
     * @param defaultValue the default; may be {@code null}
     * @return this builder
     */
    public Builder defaultValue(AIAgentConfigDefault defaultValue) {
      this.defaultValue = defaultValue;
      return this;
    }

    /**
     * Sets the interpolation variables for this agent's instructions. The map is copied defensively.
     *
     * @param variables the variables; may be {@code null}
     * @return this builder
     */
    public Builder variables(Map<String, Object> variables) {
      this.variables = variables;
      return this;
    }

    /**
     * Builds the immutable {@link AIAgentConfigRequest}.
     *
     * @return a new {@link AIAgentConfigRequest}
     */
    public AIAgentConfigRequest build() {
      return new AIAgentConfigRequest(this);
    }
  }
}
