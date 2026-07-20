package com.launchdarkly.sdk.server.ai;

import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Mode;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Model;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Provider;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * The common, mode-independent surface of a retrieved AI Config.
 * <p>
 * Instances are produced by an {@link LDAIClient} retrieval method and are always one of the
 * concrete subtypes {@link AICompletionConfig}, {@link AIAgentConfig}, or {@link AIJudgeConfig}.
 * They are immutable and safe to share across threads.
 * <p>
 * Application code does not construct these directly; supply defaults via the corresponding
 * {@code *Default} types instead.
 */
public abstract class AIConfig {
  private final String key;
  private final boolean enabled;
  private final Mode mode;
  private final Model model;
  private final Provider provider;
  private final Supplier<LDAIConfigTracker> trackerFactory;
  private final Evaluator evaluator;

  AIConfig(
      String key,
      boolean enabled,
      Mode mode,
      Model model,
      Provider provider,
      Supplier<LDAIConfigTracker> trackerFactory,
      Evaluator evaluator) {
    this.key = key;
    this.enabled = enabled;
    this.mode = mode;
    this.model = model;
    this.provider = provider;
    this.trackerFactory = Objects.requireNonNull(trackerFactory, "trackerFactory");
    this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
  }

  /**
   * Returns the key of the AI Config that was retrieved.
   *
   * @return the config key
   */
  public String getKey() {
    return key;
  }

  /**
   * Returns whether the retrieved configuration is enabled.
   * <p>
   * When {@code false}, application code should fall back to its own behavior rather than calling a
   * model provider; the other fields may be absent.
   *
   * @return {@code true} if the configuration is enabled
   */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Returns the mode of this configuration, which matches the concrete config type.
   *
   * @return the mode, never {@code null}
   */
  public Mode getMode() {
    return mode;
  }

  /**
   * Returns the model configuration.
   *
   * @return the model, or {@code null} if none was specified
   */
  public Model getModel() {
    return model;
  }

  /**
   * Returns the provider configuration.
   *
   * @return the provider, or {@code null} if none was specified
   */
  public Provider getProvider() {
    return provider;
  }

  /**
   * Creates a new tracker for a single AI run.
   * <p>
   * Each invocation is intended to create a fresh tracker for one run, so metrics from distinct
   * runs are not conflated. Call this once per AI run.
   * <p>
   * In this release the returned tracker is an internal no-op; metric reporting is implemented in a
   * later step of the AI SDK.
   *
   * @return a tracker for this configuration, never {@code null}
   */
  public LDAIConfigTracker createTracker() {
    return trackerFactory.get();
  }

  /**
   * Returns the evaluator that coordinates judge execution for this configuration.
   * <p>
   * For {@link AIJudgeConfig} this is always {@link Evaluator#noop()}. For
   * {@link AICompletionConfig} and {@link AIAgentConfig} it is the evaluator supplied at
   * construction time (also {@link Evaluator#noop()} unless a custom one is wired in).
   *
   * @return the evaluator, never {@code null}
   */
  public Evaluator getEvaluator() {
    return evaluator;
  }
}
