package com.launchdarkly.sdk.server.ai.datamodel;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A single entry from the root-level {@code tools} map of an AI Config flag variation.
 * <p>
 * This is distinct from {@code model.parameters.tools[]}, which is the raw array passed through to
 * LLM providers. Instances are immutable; build them with {@link #builder(String)}.
 */
public final class ToolConfig {
  private final String name;
  private final String description;
  private final String type;
  private final Map<String, Object> parameters;
  private final Map<String, Object> customParameters;

  private ToolConfig(
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
    if (!(o instanceof ToolConfig)) {
      return false;
    }
    ToolConfig other = (ToolConfig) o;
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
    return "ToolConfig{name=" + name + ", description=" + description + ", type=" + type
        + ", parameters=" + parameters + ", customParameters=" + customParameters + '}';
  }

  /**
   * Builder for {@link ToolConfig}.
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
     * Builds the immutable {@link ToolConfig}.
     *
     * @return a new {@link ToolConfig}
     */
    public ToolConfig build() {
      Map<String, Object> params = parameters == null
          ? Collections.<String, Object>emptyMap()
          : Collections.unmodifiableMap(new HashMap<>(parameters));
      Map<String, Object> customParams = customParameters == null
          ? Collections.<String, Object>emptyMap()
          : Collections.unmodifiableMap(new HashMap<>(customParameters));
      return new ToolConfig(name, description, type, params, customParams);
    }
  }
}
