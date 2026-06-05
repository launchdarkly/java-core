package com.launchdarkly.sdk.server.ai.datamodel;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration describing the model an AI Config should use.
 * <p>
 * Instances are immutable. The {@code parameters} and {@code custom} maps hold arbitrary values
 * decoded from the JSON protocol; their values are plain Java types ({@link String}, {@link Double},
 * {@link Boolean}, {@link java.util.List}, {@link java.util.Map}, or {@code null}). Build instances
 * with {@link #builder(String)}.
 */
public final class ModelConfig {
  private final String name;
  private final Map<String, Object> parameters;
  private final Map<String, Object> custom;

  private ModelConfig(String name, Map<String, Object> parameters, Map<String, Object> custom) {
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
    if (!(o instanceof ModelConfig)) {
      return false;
    }
    ModelConfig other = (ModelConfig) o;
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
    return "ModelConfig{name=" + name + ", parameters=" + parameters + ", custom=" + custom + '}';
  }

  /**
   * Builder for {@link ModelConfig}.
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
     * Builds the immutable {@link ModelConfig}.
     *
     * @return a new {@link ModelConfig}
     */
    public ModelConfig build() {
      Map<String, Object> params = parameters == null
          ? Collections.<String, Object>emptyMap()
          : Collections.unmodifiableMap(new HashMap<>(parameters));
      Map<String, Object> cust = custom == null
          ? Collections.<String, Object>emptyMap()
          : Collections.unmodifiableMap(new HashMap<>(custom));
      return new ModelConfig(name, params, cust);
    }
  }
}
