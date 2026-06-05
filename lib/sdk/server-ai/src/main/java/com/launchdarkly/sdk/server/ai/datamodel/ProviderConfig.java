package com.launchdarkly.sdk.server.ai.datamodel;

import java.util.Objects;

/**
 * Configuration describing the provider an AI Config should use.
 * <p>
 * Instances are immutable.
 */
public final class ProviderConfig {
  private final String name;

  /**
   * Constructs a provider configuration.
   *
   * @param name the provider name (for example {@code "openai"}); may be {@code null}
   */
  public ProviderConfig(String name) {
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
    return "ProviderConfig{name=" + name + '}';
  }
}
