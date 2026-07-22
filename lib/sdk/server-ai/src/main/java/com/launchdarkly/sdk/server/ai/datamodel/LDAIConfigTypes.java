package com.launchdarkly.sdk.server.ai.datamodel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Container for the shared, immutable AI Config data-model types.
 * <p>
 * These shapes ({@link Mode}, {@link Message}, {@link Model}, {@link Provider}, {@link Tool}, and
 * {@link JudgeConfiguration}) are used across the completion, agent, and judge configs. They are
 * grouped under this single type, rather than declared as separate top-level classes, to keep the
 * package small and to free up generic names such as {@code Message} and {@code Model}.
 * <p>
 * This class is not instantiable.
 */
public final class LDAIConfigTypes {
  private LDAIConfigTypes() {
  }

  /**
   * The mode of an AI Config, as carried by the {@code _ldMeta.mode} field of a flag variation.
   * <p>
   * The mode determines which kind of configuration a variation represents and which retrieval
   * method on the client it is valid for.
   */
  public enum Mode {
    /**
     * A completion (chat/prompt) configuration. This is the default when no mode is present.
     */
    COMPLETION("completion"),

    /**
     * An agent configuration, which carries {@code instructions} instead of {@code messages}.
     */
    AGENT("agent"),

    /**
     * A judge configuration, used to evaluate the output of another configuration.
     */
    JUDGE("judge");

    private final String wireValue;

    Mode(String wireValue) {
      this.wireValue = wireValue;
    }

    /**
     * Returns the string used to represent this mode in the JSON protocol.
     *
     * @return the wire representation (for example {@code "completion"})
     */
    public String getWireValue() {
      return wireValue;
    }

    /**
     * Resolves a wire string to a mode.
     *
     * @param value the wire value, such as {@code "agent"}; may be {@code null}
     * @return the matching mode, or {@code null} if the value is {@code null} or unrecognized
     */
    public static Mode fromWireValue(String value) {
      if (value == null) {
        return null;
      }
      for (Mode mode : values()) {
        if (mode.wireValue.equals(value)) {
          return mode;
        }
      }
      return null;
    }
  }

  /**
   * A single prompt message in an AI Config, consisting of a {@link Role} and string content.
   * <p>
   * Instances are immutable.
   */
  public static final class Message {
    /**
     * The role of a {@link Message}.
     */
    public enum Role {
      /**
       * A system message, typically used to set behavior or context.
       */
      SYSTEM("system"),

      /**
       * A message authored by the end user.
       */
      USER("user"),

      /**
       * A message authored by the assistant (model).
       */
      ASSISTANT("assistant");

      private final String wireValue;

      Role(String wireValue) {
        this.wireValue = wireValue;
      }

      /**
       * Returns the string used to represent this role in the JSON protocol.
       *
       * @return the wire representation (for example {@code "system"})
       */
      public String getWireValue() {
        return wireValue;
      }

      /**
       * Resolves a wire string to a role.
       *
       * @param value the wire value, such as {@code "user"}; may be {@code null}
       * @return the matching role, or {@code null} if the value is {@code null} or unrecognized
       */
      public static Role fromWireValue(String value) {
        if (value == null) {
          return null;
        }
        for (Role role : values()) {
          if (role.wireValue.equals(value)) {
            return role;
          }
        }
        return null;
      }
    }

    private final Role role;
    private final String content;

    /**
     * Constructs a message.
     *
     * @param role the role of the message; must not be {@code null}
     * @param content the message content; must not be {@code null}
     * @throws NullPointerException if {@code role} or {@code content} is {@code null}
     */
    public Message(Role role, String content) {
      this.role = Objects.requireNonNull(role, "role");
      this.content = Objects.requireNonNull(content, "content");
    }

    /**
     * Returns the role of this message.
     *
     * @return the role, never {@code null}
     */
    public Role getRole() {
      return role;
    }

    /**
     * Returns the content of this message.
     *
     * @return the content, never {@code null}
     */
    public String getContent() {
      return content;
    }

    /**
     * Returns a copy of this message with the given content, preserving the role.
     * <p>
     * Used by the interpolation layer to produce a rendered message without mutating the original.
     *
     * @param newContent the replacement content; must not be {@code null}
     * @return a new {@link Message}
     */
    public Message withContent(String newContent) {
      return new Message(role, newContent);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Message)) {
        return false;
      }
      Message other = (Message) o;
      return role == other.role && content.equals(other.content);
    }

    @Override
    public int hashCode() {
      return Objects.hash(role, content);
    }

    @Override
    public String toString() {
      return "Message{role=" + role + ", content=" + content + '}';
    }
  }

  /**
   * Configuration describing the model an AI Config should use.
   * <p>
   * Instances are immutable. The {@code parameters} and {@code custom} maps hold arbitrary values
   * decoded from the JSON protocol; their values are plain Java types ({@link String},
   * {@link Double}, {@link Boolean}, {@link java.util.List}, {@link java.util.Map}, or {@code null}).
   * Build instances with {@link #builder(String)}.
   */
  public static final class Model {
    private final String name;
    private final Map<String, Object> parameters;
    private final Map<String, Object> custom;

    private Model(
        String name,
        Map<String, Object> parameters,
        Map<String, Object> custom) {
      this.name = name;
      this.parameters = parameters;
      this.custom = custom;
    }

    /**
     * Returns the model name (for example {@code "gpt-4"}).
     *
     * @return the model name, or {@code null} if none was specified
     */
    public String getName() {
      return name;
    }

    /**
     * Returns the model-specific parameters as an unmodifiable map.
     *
     * @return the parameters; never {@code null} (empty when none were specified)
     */
    public Map<String, Object> getParameters() {
      return parameters;
    }

    /**
     * Returns customer-provided custom data as an unmodifiable map.
     *
     * @return the custom data; never {@code null} (empty when none was specified)
     */
    public Map<String, Object> getCustom() {
      return custom;
    }

    /**
     * Retrieves a single model parameter by key.
     *
     * @param key the parameter name
     * @return the value, or {@code null} if absent
     */
    public Object getParameter(String key) {
      return parameters.get(key);
    }

    /**
     * Retrieves a single custom-data entry by key.
     *
     * @param key the custom-data name
     * @return the value, or {@code null} if absent
     */
    public Object getCustom(String key) {
      return custom.get(key);
    }

    /**
     * Creates a builder for a model with the given name.
     *
     * @param name the model name
     * @return a new {@link Builder}
     */
    public static Builder builder(String name) {
      return new Builder(name);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Model)) {
        return false;
      }
      Model other = (Model) o;
      return Objects.equals(name, other.name)
          && parameters.equals(other.parameters)
          && custom.equals(other.custom);
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, parameters, custom);
    }

    @Override
    public String toString() {
      return "Model{name=" + name + ", parameters=" + parameters + ", custom=" + custom + '}';
    }

    /**
     * Builder for {@link Model}.
     */
    public static final class Builder {
      private final String name;
      private Map<String, Object> parameters;
      private Map<String, Object> custom;

      private Builder(String name) {
        this.name = name;
      }

      /**
       * Sets the model-specific parameters. The map is copied defensively.
       *
       * @param parameters the parameters; may be {@code null}
       * @return this builder
       */
      public Builder parameters(Map<String, Object> parameters) {
        this.parameters = parameters == null ? null : new HashMap<>(parameters);
        return this;
      }

      /**
       * Sets customer-provided custom data. The map is copied defensively.
       *
       * @param custom the custom data; may be {@code null}
       * @return this builder
       */
      public Builder custom(Map<String, Object> custom) {
        this.custom = custom == null ? null : new HashMap<>(custom);
        return this;
      }

      /**
       * Builds the immutable {@link Model}.
       *
       * @return a new {@link Model}
       */
      public Model build() {
        Map<String, Object> params = parameters == null
            ? Collections.<String, Object>emptyMap()
            : Collections.unmodifiableMap(new HashMap<>(parameters));
        Map<String, Object> cust = custom == null
            ? Collections.<String, Object>emptyMap()
            : Collections.unmodifiableMap(new HashMap<>(custom));
        return new Model(name, params, cust);
      }
    }
  }

  /**
   * Configuration describing the provider an AI Config should use.
   * <p>
   * Instances are immutable.
   */
  public static final class Provider {
    private final String name;

    /**
     * Constructs a provider configuration.
     *
     * @param name the provider name (for example {@code "openai"}); may be {@code null}
     */
    public Provider(String name) {
      this.name = name;
    }

    /**
     * Returns the provider name.
     *
     * @return the provider name, or {@code null} if none was specified
     */
    public String getName() {
      return name;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Provider)) {
        return false;
      }
      return Objects.equals(name, ((Provider) o).name);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(name);
    }

    @Override
    public String toString() {
      return "Provider{name=" + name + '}';
    }
  }

  /**
   * A single entry from the root-level {@code tools} map of an AI Config flag variation.
   * <p>
   * This is distinct from {@code model.parameters.tools[]}, which is the raw array passed through to
   * LLM providers. Instances are immutable; build them with {@link #builder(String)}.
   */
  public static final class Tool {
    private final String name;
    private final String description;
    private final String type;
    private final Map<String, Object> parameters;
    private final Map<String, Object> customParameters;

    private Tool(
        String name,
        String description,
        String type,
        Map<String, Object> parameters,
        Map<String, Object> customParameters) {
      this.name = name;
      this.description = description;
      this.type = type;
      this.parameters = parameters;
      this.customParameters = customParameters;
    }

    /**
     * Returns the tool name.
     *
     * @return the tool name, or {@code null} if none was specified
     */
    public String getName() {
      return name;
    }

    /**
     * Returns the tool description.
     *
     * @return the description, or {@code null} if none was specified
     */
    public String getDescription() {
      return description;
    }

    /**
     * Returns the tool type.
     *
     * @return the type, or {@code null} if none was specified
     */
    public String getType() {
      return type;
    }

    /**
     * Returns the tool parameters as an unmodifiable map.
     *
     * @return the parameters; never {@code null} (empty when none were specified)
     */
    public Map<String, Object> getParameters() {
      return parameters;
    }

    /**
     * Returns the tool custom parameters as an unmodifiable map.
     *
     * @return the custom parameters; never {@code null} (empty when none were specified)
     */
    public Map<String, Object> getCustomParameters() {
      return customParameters;
    }

    /**
     * Creates a builder for a tool with the given name.
     *
     * @param name the tool name
     * @return a new {@link Builder}
     */
    public static Builder builder(String name) {
      return new Builder(name);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Tool)) {
        return false;
      }
      Tool other = (Tool) o;
      return Objects.equals(name, other.name)
          && Objects.equals(description, other.description)
          && Objects.equals(type, other.type)
          && parameters.equals(other.parameters)
          && customParameters.equals(other.customParameters);
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, description, type, parameters, customParameters);
    }

    @Override
    public String toString() {
      return "Tool{name=" + name + ", description=" + description + ", type=" + type
          + ", parameters=" + parameters + ", customParameters=" + customParameters + '}';
    }

    /**
     * Builder for {@link Tool}.
     */
    public static final class Builder {
      private final String name;
      private String description;
      private String type;
      private Map<String, Object> parameters;
      private Map<String, Object> customParameters;

      private Builder(String name) {
        this.name = name;
      }

      /**
       * Sets the tool description.
       *
       * @param description the description; may be {@code null}
       * @return this builder
       */
      public Builder description(String description) {
        this.description = description;
        return this;
      }

      /**
       * Sets the tool type.
       *
       * @param type the type; may be {@code null}
       * @return this builder
       */
      public Builder type(String type) {
        this.type = type;
        return this;
      }

      /**
       * Sets the tool parameters. The map is copied defensively.
       *
       * @param parameters the parameters; may be {@code null}
       * @return this builder
       */
      public Builder parameters(Map<String, Object> parameters) {
        this.parameters = parameters == null ? null : new HashMap<>(parameters);
        return this;
      }

      /**
       * Sets the tool custom parameters. The map is copied defensively.
       *
       * @param customParameters the custom parameters; may be {@code null}
       * @return this builder
       */
      public Builder customParameters(Map<String, Object> customParameters) {
        this.customParameters = customParameters == null ? null : new HashMap<>(customParameters);
        return this;
      }

      /**
       * Builds the immutable {@link Tool}.
       *
       * @return a new {@link Tool}
       */
      public Tool build() {
        Map<String, Object> params = parameters == null
            ? Collections.<String, Object>emptyMap()
            : Collections.unmodifiableMap(new HashMap<>(parameters));
        Map<String, Object> customParams = customParameters == null
            ? Collections.<String, Object>emptyMap()
            : Collections.unmodifiableMap(new HashMap<>(customParameters));
        return new Tool(name, description, type, params, customParams);
      }
    }
  }

  /**
   * Configuration referencing the judges that may evaluate an AI Config.
   * <p>
   * This is parsed from the {@code judgeConfiguration} field of a flag variation and is visible on
   * completion and agent configs. In v1.0 judges are invoked manually; the SDK does not auto-attach
   * them. Instances are immutable.
   */
  public static final class JudgeConfiguration {
    /**
     * Configuration for a single judge attachment: which judge AI Config to use and how frequently
     * to sample it.
     * <p>
     * Instances are immutable.
     */
    public static final class Judge {
      private final String key;
      private final double samplingRate;

      /**
       * Constructs a judge attachment.
       *
       * @param key the key of the judge AI Config; must not be {@code null}
       * @param samplingRate the sampling rate, nominally in the range {@code 0.0}–{@code 1.0}
       * @throws NullPointerException if {@code key} is {@code null}
       */
      public Judge(String key, double samplingRate) {
        this.key = Objects.requireNonNull(key, "key");
        this.samplingRate = samplingRate;
      }

      /**
       * Returns the key of the judge AI Config.
       *
       * @return the judge key, never {@code null}
       */
      public String getKey() {
        return key;
      }

      /**
       * Returns the configured sampling rate.
       *
       * @return the sampling rate
       */
      public double getSamplingRate() {
        return samplingRate;
      }

      @Override
      public boolean equals(Object o) {
        if (this == o) {
          return true;
        }
        if (!(o instanceof Judge)) {
          return false;
        }
        Judge other = (Judge) o;
        return Double.compare(samplingRate, other.samplingRate) == 0 && key.equals(other.key);
      }

      @Override
      public int hashCode() {
        return Objects.hash(key, samplingRate);
      }

      @Override
      public String toString() {
        return "Judge{key=" + key + ", samplingRate=" + samplingRate + '}';
      }
    }

    private final List<Judge> judges;

    /**
     * Constructs a judge configuration.
     *
     * @param judges the judge attachments; may be {@code null}, treated as empty
     */
    public JudgeConfiguration(List<Judge> judges) {
      this.judges = judges == null
          ? Collections.<Judge>emptyList()
          : Collections.unmodifiableList(new ArrayList<>(judges));
    }

    /**
     * Returns the configured judge attachments as an unmodifiable list.
     *
     * @return the judges; never {@code null} (empty when none were specified)
     */
    public List<Judge> getJudges() {
      return judges;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof JudgeConfiguration)) {
        return false;
      }
      return judges.equals(((JudgeConfiguration) o).judges);
    }

    @Override
    public int hashCode() {
      return judges.hashCode();
    }

    @Override
    public String toString() {
      return "JudgeConfiguration{judges=" + judges + '}';
    }
  }
}
