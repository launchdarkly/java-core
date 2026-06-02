package com.launchdarkly.sdk.server.ai.datamodel;

import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.LDValueType;
import com.launchdarkly.sdk.ObjectBuilder;

import java.util.Objects;

/**
 * Configuration describing the model associated with an AI Config, including the model name and
 * any provider-specific parameters or custom data.
 */
public final class ModelConfig {
  private final String name;
  private final LDValue parameters;
  private final LDValue custom;

  /**
   * Creates a model configuration with just a name.
   *
   * @param name the name of the model
   */
  public ModelConfig(String name) {
    this(name, LDValue.ofNull(), LDValue.ofNull());
  }

  /**
   * Creates a model configuration.
   *
   * @param name the name of the model
   * @param parameters model-specific parameters as a JSON object, or {@link LDValue#ofNull()}
   * @param custom additional customer-provided data as a JSON object, or {@link LDValue#ofNull()}
   */
  public ModelConfig(String name, LDValue parameters, LDValue custom) {
    this.name = name;
    this.parameters = parameters == null ? LDValue.ofNull() : parameters;
    this.custom = custom == null ? LDValue.ofNull() : custom;
  }

  /**
   * Returns the name of the model.
   *
   * @return the model name
   */
  public String getName() {
    return name;
  }

  /**
   * Retrieves a model parameter by key.
   * <p>
   * Requesting the key {@code "name"} returns the model name. Any other key is looked up in the
   * model parameters.
   *
   * @param key the parameter key
   * @return the value, or {@link LDValue#ofNull()} if not present
   */
  public LDValue getParameter(String key) {
    if ("name".equals(key)) {
      return LDValue.of(name);
    }
    if (parameters.getType() != LDValueType.OBJECT) {
      return LDValue.ofNull();
    }
    return parameters.get(key);
  }

  /**
   * Retrieves a custom value by key.
   *
   * @param key the custom data key
   * @return the value, or {@link LDValue#ofNull()} if not present
   */
  public LDValue getCustom(String key) {
    if (custom.getType() != LDValueType.OBJECT) {
      return LDValue.ofNull();
    }
    return custom.get(key);
  }

  /**
   * Returns the full set of model parameters.
   *
   * @return the parameters object, or {@link LDValue#ofNull()} if none were provided
   */
  public LDValue getParameters() {
    return parameters;
  }

  /**
   * Returns the full set of custom data.
   *
   * @return the custom object, or {@link LDValue#ofNull()} if none was provided
   */
  public LDValue getCustom() {
    return custom;
  }

  /**
   * Renders this model config as an {@link LDValue} object.
   *
   * @return the JSON representation
   */
  public LDValue toLDValue() {
    return LDValue.buildObject()
        .put("name", name)
        .put("parameters", parameters)
        .put("custom", custom)
        .build();
  }

  /**
   * Creates a builder for a model configuration.
   *
   * @param name the name of the model
   * @return a new builder
   */
  public static Builder builder(String name) {
    return new Builder(name);
  }

  /**
   * A builder for {@link ModelConfig} instances.
   */
  public static final class Builder {
    private final String name;
    private final ObjectBuilder parameters = LDValue.buildObject();
    private final ObjectBuilder custom = LDValue.buildObject();
    private boolean hasParameters = false;
    private boolean hasCustom = false;

    private Builder(String name) {
      this.name = name;
    }

    /**
     * Adds a model parameter.
     *
     * @param key the parameter key
     * @param value the parameter value
     * @return this builder
     */
    public Builder parameter(String key, LDValue value) {
      parameters.put(key, value);
      hasParameters = true;
      return this;
    }

    /**
     * Adds a custom data entry.
     *
     * @param key the custom data key
     * @param value the custom data value
     * @return this builder
     */
    public Builder custom(String key, LDValue value) {
      custom.put(key, value);
      hasCustom = true;
      return this;
    }

    /**
     * Builds the model configuration.
     *
     * @return a new {@link ModelConfig}
     */
    public ModelConfig build() {
      return new ModelConfig(
          name,
          hasParameters ? parameters.build() : LDValue.ofNull(),
          hasCustom ? custom.build() : LDValue.ofNull());
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ModelConfig)) {
      return false;
    }
    ModelConfig other = (ModelConfig) o;
    return Objects.equals(name, other.name)
        && Objects.equals(parameters, other.parameters)
        && Objects.equals(custom, other.custom);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, parameters, custom);
  }

  @Override
  public String toString() {
    return "ModelConfig{name=" + name + ", parameters=" + parameters + ", custom=" + custom + "}";
  }
}
