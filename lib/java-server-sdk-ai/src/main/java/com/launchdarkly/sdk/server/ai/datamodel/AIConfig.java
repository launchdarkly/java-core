package com.launchdarkly.sdk.server.ai.datamodel;

import com.launchdarkly.sdk.server.ai.tracking.AIConfigTracker;

import java.util.function.Supplier;

/**
 * Base type for the AI Config variants returned by {@code LDAIClient}.
 * <p>
 * All AI Config types share a key, an enabled flag, optional model and provider configuration, and
 * the ability to mint a fresh {@link AIConfigTracker} for an AI run via {@link #createTracker()}.
 */
public abstract class AIConfig {
  private final String key;
  private final boolean enabled;
  private final ModelConfig model;
  private final ProviderConfig provider;
  private final Supplier<AIConfigTracker> trackerFactory;

  /**
   * Constructs a base AI Config.
   *
   * @param key the configuration key
   * @param enabled whether the configuration is enabled
   * @param model the model configuration, or {@code null}
   * @param provider the provider configuration, or {@code null}
   * @param trackerFactory a factory that produces a fresh tracker per invocation
   */
  protected AIConfig(
      String key,
      boolean enabled,
      ModelConfig model,
      ProviderConfig provider,
      Supplier<AIConfigTracker> trackerFactory) {
    this.key = key;
    this.enabled = enabled;
    this.model = model;
    this.provider = provider;
    this.trackerFactory = trackerFactory;
  }

  /**
   * Returns the configuration key used for tracking and identification.
   *
   * @return the key
   */
  public String getKey() {
    return key;
  }

  /**
   * Returns whether the configuration is enabled.
   *
   * @return {@code true} if enabled
   */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Returns the model configuration.
   *
   * @return the model configuration, or {@code null} if none was provided
   */
  public ModelConfig getModel() {
    return model;
  }

  /**
   * Returns the provider configuration.
   *
   * @return the provider configuration, or {@code null} if none was provided
   */
  public ProviderConfig getProvider() {
    return provider;
  }

  /**
   * Creates a new {@link AIConfigTracker} for a single AI run.
   * <p>
   * Each call mints a tracker with a new {@code runId} (a UUIDv4) so that LaunchDarkly can correlate
   * the run's events in metrics views. Call this once per AI run; metrics from different runs cannot
   * be combined.
   *
   * @return a fresh tracker
   */
  public AIConfigTracker createTracker() {
    return trackerFactory.get();
  }
}
