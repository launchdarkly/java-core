package com.launchdarkly.sdk.server.ai.datamodel;

import com.launchdarkly.sdk.ArrayBuilder;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.ObjectBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A user-constructed default value for {@code LDAIClient.completionConfig}.
 */
public final class AICompletionConfigDefault extends AIConfigDefault {
  private final List<LDMessage> messages;
  private final JudgeConfiguration judgeConfiguration;
  private final Map<String, LDTool> tools;

  private AICompletionConfigDefault(Builder builder) {
    super(builder.enabled, builder.model, builder.provider);
    this.messages = builder.messages;
    this.judgeConfiguration = builder.judgeConfiguration;
    this.tools = builder.tools;
  }

  /**
   * Returns a disabled default.
   *
   * @return a default with {@code enabled} set to {@code false}
   */
  public static AICompletionConfigDefault disabled() {
    return builder().enabled(false).build();
  }

  /**
   * Returns the default prompt messages.
   *
   * @return the messages, or {@code null} if none were provided
   */
  public List<LDMessage> getMessages() {
    return messages;
  }

  /**
   * Returns the default judge configuration.
   *
   * @return the judge configuration, or {@code null} if none was provided
   */
  public JudgeConfiguration getJudgeConfiguration() {
    return judgeConfiguration;
  }

  /**
   * Returns the default tools map.
   *
   * @return the tools, or {@code null} if none were provided
   */
  public Map<String, LDTool> getTools() {
    return tools;
  }

  @Override
  public LDValue toLDValue() {
    ObjectBuilder builder = baseObject();
    builder.put("messages", messagesToLDValue(messages));
    if (judgeConfiguration != null) {
      builder.put("judgeConfiguration", judgeConfiguration.toLDValue());
    }
    if (tools != null) {
      builder.put("tools", toolsToLDValue(tools));
    }
    return builder.build();
  }

  static LDValue messagesToLDValue(List<LDMessage> messages) {
    if (messages == null) {
      return LDValue.ofNull();
    }
    ArrayBuilder array = LDValue.buildArray();
    for (LDMessage message : messages) {
      array.add(message.toLDValue());
    }
    return array.build();
  }

  static LDValue toolsToLDValue(Map<String, LDTool> tools) {
    ObjectBuilder object = LDValue.buildObject();
    for (Map.Entry<String, LDTool> entry : tools.entrySet()) {
      object.put(entry.getKey(), entry.getValue().toLDValue());
    }
    return object.build();
  }

  /**
   * Creates a builder for an {@link AICompletionConfigDefault}.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * A builder for {@link AICompletionConfigDefault} instances.
   */
  public static final class Builder {
    private Boolean enabled;
    private ModelConfig model;
    private ProviderConfig provider;
    private List<LDMessage> messages;
    private JudgeConfiguration judgeConfiguration;
    private Map<String, LDTool> tools;

    private Builder() {
    }

    /** @param enabled whether the config should be considered enabled @return this builder */
    public Builder enabled(boolean enabled) {
      this.enabled = enabled;
      return this;
    }

    /** @param model the model configuration @return this builder */
    public Builder model(ModelConfig model) {
      this.model = model;
      return this;
    }

    /** @param provider the provider configuration @return this builder */
    public Builder provider(ProviderConfig provider) {
      this.provider = provider;
      return this;
    }

    /** @param messages the default prompt messages @return this builder */
    public Builder messages(List<LDMessage> messages) {
      this.messages = messages == null ? null : new ArrayList<>(messages);
      return this;
    }

    /** @param judgeConfiguration the default judge configuration @return this builder */
    public Builder judgeConfiguration(JudgeConfiguration judgeConfiguration) {
      this.judgeConfiguration = judgeConfiguration;
      return this;
    }

    /** @param tools the default tools map @return this builder */
    public Builder tools(Map<String, LDTool> tools) {
      this.tools = tools;
      return this;
    }

    /**
     * Builds the default.
     *
     * @return a new {@link AICompletionConfigDefault}
     */
    public AICompletionConfigDefault build() {
      return new AICompletionConfigDefault(this);
    }
  }
}
