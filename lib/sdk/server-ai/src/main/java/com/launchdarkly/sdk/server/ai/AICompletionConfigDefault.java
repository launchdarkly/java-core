package com.launchdarkly.sdk.server.ai;

import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.JudgeConfiguration;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Message;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A caller-supplied default for {@link LDAIClient#completionConfig}, returned (as an
 * {@link AICompletionConfig}) when the flag is absent or cannot be evaluated.
 * <p>
 * Build instances with {@link #builder()}. Instances are immutable.
 */
public final class AICompletionConfigDefault extends AIConfigDefault {
  private final List<Message> messages;
  private final JudgeConfiguration judgeConfiguration;
  private final Map<String, Tool> tools;

  private AICompletionConfigDefault(Builder builder) {
    super(builder);
    this.messages = builder.messages == null
        ? null : Collections.unmodifiableList(new ArrayList<>(builder.messages));
    this.judgeConfiguration = builder.judgeConfiguration;
    this.tools = builder.tools == null
        ? null : Collections.unmodifiableMap(new LinkedHashMap<>(builder.tools));
  }

  /**
   * Returns the default prompt messages.
   *
   * @return an unmodifiable list of messages, or {@code null} if none were specified
   */
  public List<Message> getMessages() {
    return messages;
  }

  /**
   * Returns the default judge configuration.
   *
   * @return the judge configuration, or {@code null} if none was specified
   */
  public JudgeConfiguration getJudgeConfiguration() {
    return judgeConfiguration;
  }

  /**
   * Returns the default root-level tools map.
   *
   * @return an unmodifiable map of tools, or {@code null} if none were specified
   */
  public Map<String, Tool> getTools() {
    return tools;
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
   * Returns a disabled default, suitable as a fallback that causes callers to skip the model.
   *
   * @return a disabled {@link AICompletionConfigDefault}
   */
  public static AICompletionConfigDefault disabled() {
    return builder().enabled(false).build();
  }

  /**
   * Builder for {@link AICompletionConfigDefault}.
   */
  public static final class Builder extends AbstractBuilder<Builder> {
    private List<Message> messages;
    private JudgeConfiguration judgeConfiguration;
    private Map<String, Tool> tools;

    private Builder() {
    }

    @Override
    protected Builder self() {
      return this;
    }

    /**
     * Sets the default prompt messages. The list is copied defensively.
     *
     * @param messages the messages; may be {@code null}
     * @return this builder
     */
    public Builder messages(List<Message> messages) {
      this.messages = messages;
      return this;
    }

    /**
     * Sets the default judge configuration.
     *
     * @param judgeConfiguration the judge configuration; may be {@code null}
     * @return this builder
     */
    public Builder judgeConfiguration(JudgeConfiguration judgeConfiguration) {
      this.judgeConfiguration = judgeConfiguration;
      return this;
    }

    /**
     * Sets the default root-level tools map. The map is copied defensively.
     *
     * @param tools the tools; may be {@code null}
     * @return this builder
     */
    public Builder tools(Map<String, Tool> tools) {
      this.tools = tools;
      return this;
    }

    /**
     * Builds the immutable {@link AICompletionConfigDefault}.
     *
     * @return a new {@link AICompletionConfigDefault}
     */
    public AICompletionConfigDefault build() {
      return new AICompletionConfigDefault(this);
    }
  }
}
