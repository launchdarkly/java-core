package com.launchdarkly.sdk.server.ai.datamodel;

import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.ObjectBuilder;

/**
 * Base type for the user-constructed default values passed to the {@code LDAIClient} configuration
 * methods. A default is used as the fallback value when no flag variation is available.
 */
public abstract class AIConfigDefault {
  private final Boolean enabled;
  private final ModelConfig model;
  private final ProviderConfig provider;

  /**
   * Constructs a base default.
   *
   * @param enabled whether the config should be considered enabled, or {@code null} to leave unset
   * @param model the model configuration, or {@code null}
   * @param provider the provider configuration, or {@code null}
   */
  protected AIConfigDefault(Boolean enabled, ModelConfig model, ProviderConfig provider) {
    this.enabled = enabled;
    this.model = model;
    this.provider = provider;
  }

  /**
   * Returns whether the config should be considered enabled.
   *
   * @return the enabled flag, or {@code null} if unset
   */
  public Boolean getEnabled() {
    return enabled;
  }

  /**
   * Returns the model configuration.
   *
   * @return the model configuration, or {@code null}
   */
  public ModelConfig getModel() {
    return model;
  }

  /**
   * Returns the provider configuration.
   *
   * @return the provider configuration, or {@code null}
   */
  public ProviderConfig getProvider() {
    return provider;
  }

  /**
   * Builds an {@link LDValue} object containing the fields common to all default types, suitable for
   * use as the default value of a JSON flag evaluation.
   *
   * @return an object builder seeded with {@code _ldMeta}, {@code model}, and {@code provider}
   */
  protected ObjectBuilder baseObject() {
    LDValue ldMeta = LDValue.buildObject()
        .put("enabled", enabled != null && enabled)
        .build();
    return LDValue.buildObject()
        .put("_ldMeta", ldMeta)
        .put("model", model == null ? LDValue.ofNull() : model.toLDValue())
        .put("provider", provider == null ? LDValue.ofNull() : provider.toLDValue());
  }

  /**
   * Renders this default value as an {@link LDValue} object.
   *
   * @return the JSON representation
   */
  public abstract LDValue toLDValue();
}
