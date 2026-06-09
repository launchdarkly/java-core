package com.launchdarkly.sdk.server.ai;

import com.launchdarkly.sdk.server.ai.datamodel.ModelConfig;
import com.launchdarkly.sdk.server.ai.datamodel.ProviderConfig;

/**
 * The common, mode-independent surface of a caller-supplied default AI Config.
 * <p>
 * A default is passed to an {@link LDAIClient} retrieval method and is returned (as the
 * corresponding concrete config type) when the flag is absent or cannot be evaluated. Concrete
 * subtypes are {@link AICompletionConfigDefault}, {@link AIAgentConfigDefault}, and
 * {@link AIJudgeConfigDefault}; build them with their {@code builder()} methods. Instances are
 * immutable.
 */
public abstract class AIConfigDefault {
  private final Boolean enabled;
  private final ModelConfig model;
  private final ProviderConfig provider;

  AIConfigDefault(AbstractBuilder<?> builder) {
    this.enabled = builder.enabled;
    this.model = builder.model;
    this.provider = builder.provider;
  }

  /**
   * Returns the configured enabled flag.
   *
   * @return the enabled flag, or {@code null} if it was not set (treated as disabled)
   */
  public Boolean getEnabled() {
    return enabled;
  }

  /**
   * Returns {@code true} only if the enabled flag was explicitly set to {@code true}.
   *
   * @return whether the default is enabled, defaulting to {@code false} when unset
   */
  public boolean isEnabled() {
    return Boolean.TRUE.equals(enabled);
  }

  /**
   * Returns the model configuration.
   *
   * @return the model, or {@code null} if none was specified
   */
  public ModelConfig getModel() {
    return model;
  }

  /**
   * Returns the provider configuration.
   *
   * @return the provider, or {@code null} if none was specified
   */
  public ProviderConfig getProvider() {
    return provider;
  }

  /**
   * Base builder holding the fields shared by every default config type.
   * <p>
   * Uses the curiously recurring generic pattern so that the shared setters return the concrete
   * builder subtype for fluent chaining.
   *
   * @param <B> the concrete builder subtype
   */
  protected abstract static class AbstractBuilder<B extends AbstractBuilder<B>> {
    private Boolean enabled;
    private ModelConfig model;
    private ProviderConfig provider;

    /**
     * Returns this builder as the concrete subtype.
     *
     * @return this builder
     */
    protected abstract B self();

    /**
     * Sets whether the default configuration is enabled.
     *
     * @param enabled whether the configuration is enabled
     * @return this builder
     */
    public B enabled(boolean enabled) {
      this.enabled = enabled;
      return self();
    }

    /**
     * Sets the model configuration.
     *
     * @param model the model configuration; may be {@code null}
     * @return this builder
     */
    public B model(ModelConfig model) {
      this.model = model;
      return self();
    }

    /**
     * Sets the provider configuration.
     *
     * @param provider the provider configuration; may be {@code null}
     * @return this builder
     */
    public B provider(ProviderConfig provider) {
      this.provider = provider;
      return self();
    }
  }
}
