package com.launchdarkly.sdk.server.ai.internal;

import com.launchdarkly.sdk.server.ai.datamodel.AIConfigMode;
import com.launchdarkly.sdk.server.ai.datamodel.JudgeConfiguration;
import com.launchdarkly.sdk.server.ai.datamodel.LDMessage;
import com.launchdarkly.sdk.server.ai.datamodel.ModelConfig;
import com.launchdarkly.sdk.server.ai.datamodel.ProviderConfig;
import com.launchdarkly.sdk.server.ai.datamodel.ToolConfig;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The parsed, strongly-typed representation of an AI Config flag variation's JSON protocol.
 * <p>
 * This mirrors the wire structure: the {@code _ldMeta} block (enabled / variationKey / version /
 * mode) plus the model, provider, messages, instructions, tools, judge configuration, and resolved
 * evaluation metric key. It is produced by {@link AIConfigParser} and consumed when assembling the
 * public config types (in a later step).
 * <p>
 * Boxed types are used for {@code _ldMeta} scalars so that "absent" is distinguishable from a
 * concrete value; callers decide the defaults (for example {@code enabled == null} means "treat as
 * disabled" and {@code mode == null} means "treat as completion"). Instances are immutable.
 * <p>
 * This class is an internal implementation detail and is not part of the supported API.
 */
public final class AIConfigFlagValue {
  private final Boolean enabled;
  private final String variationKey;
  private final Integer version;
  private final AIConfigMode mode;
  private final ModelConfig model;
  private final ProviderConfig provider;
  private final List<LDMessage> messages;
  private final String instructions;
  private final Map<String, ToolConfig> tools;
  private final JudgeConfiguration judgeConfiguration;
  private final String evaluationMetricKey;

  private AIConfigFlagValue(Builder b) {
    this.enabled = b.enabled;
    this.variationKey = b.variationKey;
    this.version = b.version;
    this.mode = b.mode;
    this.model = b.model;
    this.provider = b.provider;
    this.messages = b.messages == null ? null : Collections.unmodifiableList(b.messages);
    this.instructions = b.instructions;
    this.tools = b.tools == null ? null : Collections.unmodifiableMap(b.tools);
    this.judgeConfiguration = b.judgeConfiguration;
    this.evaluationMetricKey = b.evaluationMetricKey;
  }

  /**
   * Returns the {@code _ldMeta.enabled} flag.
   *
   * @return the enabled flag, or {@code null} if absent
   */
  public Boolean getEnabled() {
    return enabled;
  }

  /**
   * Returns {@code true} if {@code _ldMeta.enabled} is explicitly {@code true}.
   *
   * @return whether the config is enabled, defaulting to {@code false} when absent
   */
  public boolean isEnabled() {
    return Boolean.TRUE.equals(enabled);
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
   * Returns the {@code _ldMeta.version}.
   *
   * @return the version, or {@code null} if absent
   */
  public Integer getVersion() {
    return version;
  }

  /**
   * Returns the {@code _ldMeta.mode}.
   *
   * @return the mode, or {@code null} if absent or unrecognized
   */
  public AIConfigMode getMode() {
    return mode;
  }

  /**
   * Returns the model configuration.
   *
   * @return the model, or {@code null} if absent
   */
  public ModelConfig getModel() {
    return model;
  }

  /**
   * Returns the provider configuration.
   *
   * @return the provider, or {@code null} if absent
   */
  public ProviderConfig getProvider() {
    return provider;
  }

  /**
   * Returns the prompt messages.
   *
   * @return an unmodifiable list of messages, or {@code null} if absent
   */
  public List<LDMessage> getMessages() {
    return messages;
  }

  /**
   * Returns the agent instructions.
   *
   * @return the instructions, or {@code null} if absent
   */
  public String getInstructions() {
    return instructions;
  }

  /**
   * Returns the resolved root-level tools map.
   *
   * @return an unmodifiable map keyed by tool name, or {@code null} if absent
   */
  public Map<String, ToolConfig> getTools() {
    return tools;
  }

  /**
   * Returns the judge configuration.
   *
   * @return the judge configuration, or {@code null} if absent
   */
  public JudgeConfiguration getJudgeConfiguration() {
    return judgeConfiguration;
  }

  /**
   * Returns the resolved evaluation metric key.
   *
   * @return the metric key, or {@code null} if none was resolved
   */
  public String getEvaluationMetricKey() {
    return evaluationMetricKey;
  }

  /**
   * Creates a new builder.
   *
   * @return a new {@link Builder}
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builder for {@link AIConfigFlagValue}.
   */
  public static final class Builder {
    private Boolean enabled;
    private String variationKey;
    private Integer version;
    private AIConfigMode mode;
    private ModelConfig model;
    private ProviderConfig provider;
    private List<LDMessage> messages;
    private String instructions;
    private Map<String, ToolConfig> tools;
    private JudgeConfiguration judgeConfiguration;
    private String evaluationMetricKey;

    private Builder() {
    }

    /**
     * Sets the enabled flag.
     *
     * @param v the enabled flag
     * @return this builder
     */
    public Builder enabled(Boolean v) {
      this.enabled = v;
      return this;
    }

    /**
     * Sets the variation key.
     *
     * @param v the variation key
     * @return this builder
     */
    public Builder variationKey(String v) {
      this.variationKey = v;
      return this;
    }

    /**
     * Sets the version.
     *
     * @param v the version
     * @return this builder
     */
    public Builder version(Integer v) {
      this.version = v;
      return this;
    }

    /**
     * Sets the mode.
     *
     * @param v the mode
     * @return this builder
     */
    public Builder mode(AIConfigMode v) {
      this.mode = v;
      return this;
    }

    /**
     * Sets the model configuration.
     *
     * @param v the model
     * @return this builder
     */
    public Builder model(ModelConfig v) {
      this.model = v;
      return this;
    }

    /**
     * Sets the provider configuration.
     *
     * @param v the provider
     * @return this builder
     */
    public Builder provider(ProviderConfig v) {
      this.provider = v;
      return this;
    }

    /**
     * Sets the prompt messages.
     *
     * @param v the messages
     * @return this builder
     */
    public Builder messages(List<LDMessage> v) {
      this.messages = v;
      return this;
    }

    /**
     * Sets the agent instructions.
     *
     * @param v the instructions
     * @return this builder
     */
    public Builder instructions(String v) {
      this.instructions = v;
      return this;
    }

    /**
     * Sets the resolved tools map.
     *
     * @param v the tools
     * @return this builder
     */
    public Builder tools(Map<String, ToolConfig> v) {
      this.tools = v;
      return this;
    }

    /**
     * Sets the judge configuration.
     *
     * @param v the judge configuration
     * @return this builder
     */
    public Builder judgeConfiguration(JudgeConfiguration v) {
      this.judgeConfiguration = v;
      return this;
    }

    /**
     * Sets the resolved evaluation metric key.
     *
     * @param v the evaluation metric key
     * @return this builder
     */
    public Builder evaluationMetricKey(String v) {
      this.evaluationMetricKey = v;
      return this;
    }

    /**
     * Builds the immutable {@link AIConfigFlagValue}.
     *
     * @return a new {@link AIConfigFlagValue}
     */
    public AIConfigFlagValue build() {
      return new AIConfigFlagValue(this);
    }
  }
}
