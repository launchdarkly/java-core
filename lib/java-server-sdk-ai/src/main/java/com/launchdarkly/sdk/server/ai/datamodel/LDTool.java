package com.launchdarkly.sdk.server.ai.datamodel;

import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.ObjectBuilder;

import java.util.Objects;

/**
 * A single tool entry from the root-level {@code tools} map of an AI Config.
 * <p>
 * This is distinct from {@code model.parameters.tools[]}, which is the raw array passed to LLM
 * providers unmodified. The root-level tools map carries additional metadata such as
 * {@link #getCustomParameters() customParameters} that should not be forwarded to the provider.
 */
public final class LDTool {
  private final String name;
  private final String description;
  private final String type;
  private final LDValue parameters;
  private final LDValue customParameters;

  private LDTool(Builder builder) {
    this.name = builder.name;
    this.description = builder.description;
    this.type = builder.type;
    this.parameters = builder.parameters == null ? LDValue.ofNull() : builder.parameters;
    this.customParameters = builder.customParameters == null ? LDValue.ofNull() : builder.customParameters;
  }

  /**
   * Returns the tool name (which matches its key in the tools map).
   *
   * @return the tool name
   */
  public String getName() {
    return name;
  }

  /**
   * Returns the human-readable description of what the tool does.
   *
   * @return the description, or {@code null} if not specified
   */
  public String getDescription() {
    return description;
  }

  /**
   * Returns the tool type (for example {@code "function"}).
   *
   * @return the type, or {@code null} if not specified
   */
  public String getType() {
    return type;
  }

  /**
   * Returns the JSON Schema describing the tool's input parameters.
   *
   * @return the parameters, or {@link LDValue#ofNull()} if not specified
   */
  public LDValue getParameters() {
    return parameters;
  }

  /**
   * Returns custom parameters that are not passed to the LLM provider.
   *
   * @return the custom parameters, or {@link LDValue#ofNull()} if not specified
   */
  public LDValue getCustomParameters() {
    return customParameters;
  }

  /**
   * Renders this tool as an {@link LDValue} object using the wire format (with the
   * {@code customParameters} camelCase key).
   *
   * @return the JSON representation
   */
  public LDValue toLDValue() {
    ObjectBuilder builder = LDValue.buildObject().put("name", name);
    if (description != null) {
      builder.put("description", description);
    }
    if (type != null) {
      builder.put("type", type);
    }
    if (!parameters.isNull()) {
      builder.put("parameters", parameters);
    }
    if (!customParameters.isNull()) {
      builder.put("customParameters", customParameters);
    }
    return builder.build();
  }

  /**
   * Creates a builder for a tool with the given name.
   *
   * @param name the tool name
   * @return a new builder
   */
  public static Builder builder(String name) {
    return new Builder(name);
  }

  /**
   * A builder for {@link LDTool} instances.
   */
  public static final class Builder {
    private final String name;
    private String description;
    private String type;
    private LDValue parameters;
    private LDValue customParameters;

    private Builder(String name) {
      this.name = name;
    }

    /**
     * Sets the tool description.
     *
     * @param description the description
     * @return this builder
     */
    public Builder description(String description) {
      this.description = description;
      return this;
    }

    /**
     * Sets the tool type.
     *
     * @param type the type
     * @return this builder
     */
    public Builder type(String type) {
      this.type = type;
      return this;
    }

    /**
     * Sets the JSON Schema describing the tool's parameters.
     *
     * @param parameters the parameters
     * @return this builder
     */
    public Builder parameters(LDValue parameters) {
      this.parameters = parameters;
      return this;
    }

    /**
     * Sets custom parameters that are not passed to the LLM provider.
     *
     * @param customParameters the custom parameters
     * @return this builder
     */
    public Builder customParameters(LDValue customParameters) {
      this.customParameters = customParameters;
      return this;
    }

    /**
     * Builds the tool.
     *
     * @return a new {@link LDTool}
     */
    public LDTool build() {
      return new LDTool(this);
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof LDTool)) {
      return false;
    }
    LDTool other = (LDTool) o;
    return Objects.equals(name, other.name)
        && Objects.equals(description, other.description)
        && Objects.equals(type, other.type)
        && Objects.equals(parameters, other.parameters)
        && Objects.equals(customParameters, other.customParameters);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, description, type, parameters, customParameters);
  }

  @Override
  public String toString() {
    return "LDTool{name=" + name + ", type=" + type + "}";
  }
}
