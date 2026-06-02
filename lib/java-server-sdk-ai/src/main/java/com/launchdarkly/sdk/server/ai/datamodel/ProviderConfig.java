package com.launchdarkly.sdk.server.ai.datamodel;

import com.launchdarkly.sdk.LDValue;

import java.util.Objects;

/**
 * Configuration describing the AI provider associated with an AI Config (for example
 * {@code "openai"}).
 */
public final class ProviderConfig {
  private final String name;

  /**
   * Creates a provider configuration.
   *
   * @param name the name of the provider
   */
  public ProviderConfig(String name) {
    this.name = name;
  }

  /**
   * Returns the name of the provider.
   *
   * @return the provider name
   */
  public String getName() {
    return name;
  }

  /**
   * Renders this provider config as an {@link LDValue} object.
   *
   * @return the JSON representation
   */
  public LDValue toLDValue() {
    return LDValue.buildObject().put("name", name).build();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ProviderConfig)) {
      return false;
    }
    return Objects.equals(name, ((ProviderConfig) o).name);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(name);
  }

  @Override
  public String toString() {
    return "ProviderConfig{name=" + name + "}";
  }
}
