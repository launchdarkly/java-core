package com.launchdarkly.sdk.server.ai;

import com.launchdarkly.sdk.LDContext;

import java.util.List;
import java.util.Map;

/**
 * The LaunchDarkly Server-Side AI client, for retrieving AI Configs.
 * <p>
 * An {@code LDAIClient} wraps an initialized server-side {@code LDClient}. Each retrieval method
 * evaluates the AI Config flag for the given key and context, validates that the variation's mode
 * matches the requested kind, interpolates any prompt messages or instructions with the supplied
 * variables (and the evaluation context, exposed to templates as {@code ldctx}), and returns a
 * strongly-typed config.
 * <p>
 * When the flag is absent, cannot be evaluated, or its mode does not match the requested kind, the
 * caller-supplied default is returned as the corresponding config type (a warning is logged on a
 * mode mismatch); a config is never returned in a state that would force the caller into a
 * {@code NullPointerException}.
 * <p>
 * Implementations are thread-safe.
 */
public interface LDAIClient {
  /**
   * Retrieves a completion (chat/prompt) AI Config.
   *
   * @param key the AI Config key
   * @param context the context to evaluate the configuration in
   * @param defaultValue the default returned when the flag is absent or cannot be evaluated; when
   *     {@code null}, a disabled default is used
   * @param variables variables interpolated into the prompt messages; may be {@code null}
   * @return the completion config, never {@code null}
   */
  AICompletionConfig completionConfig(
      String key,
      LDContext context,
      AICompletionConfigDefault defaultValue,
      Map<String, Object> variables);

  /**
   * Retrieves a single agent AI Config.
   *
   * @param key the AI Config key
   * @param context the context to evaluate the configuration in
   * @param defaultValue the default returned when the flag is absent or cannot be evaluated; when
   *     {@code null}, a disabled default is used
   * @param variables variables interpolated into the agent instructions; may be {@code null}
   * @return the agent config, never {@code null}
   */
  AIAgentConfig agentConfig(
      String key,
      LDContext context,
      AIAgentConfigDefault defaultValue,
      Map<String, Object> variables);

  /**
   * Retrieves multiple agent AI Configs in a single call.
   * <p>
   * Each request carries its own key, default, and interpolation variables. The returned map is
   * keyed by agent key and preserves the order of the requests.
   *
   * @param agentConfigs the agent requests to retrieve
   * @param context the context to evaluate the configurations in
   * @return a map of agent key to its retrieved {@link AIAgentConfig}, never {@code null}
   */
  Map<String, AIAgentConfig> agentConfigs(List<AIAgentConfigRequest> agentConfigs, LDContext context);

  /**
   * Retrieves a judge AI Config.
   *
   * @param key the AI Config key
   * @param context the context to evaluate the configuration in
   * @param defaultValue the default returned when the flag is absent or cannot be evaluated; when
   *     {@code null}, a disabled default is used
   * @param variables variables interpolated into the prompt messages; may be {@code null}
   * @return the judge config, never {@code null}
   */
  AIJudgeConfig judgeConfig(
      String key,
      LDContext context,
      AIJudgeConfigDefault defaultValue,
      Map<String, Object> variables);

  /**
   * Reconstructs a tracker from a resumption token produced by
   * {@link LDAIConfigTracker#getResumptionToken()}.
   * <p>
   * The reconstructed tracker shares the original run's {@code runId}, so events it emits (for
   * example deferred user feedback recorded in another process) correlate with the original AI run.
   * Model and provider names are not carried in the token and are reported as empty strings.
   *
   * @param resumptionToken the token to reconstruct from
   * @param context the context the tracker's events will be attributed to
   * @return a tracker sharing the original run's identity
   * @throws IllegalArgumentException if the token is malformed
   */
  LDAIConfigTracker createTracker(String resumptionToken, LDContext context);

  /**
   * Retrieves a judge AI Config and builds a {@link Judge} for manual evaluation.
   * <p>
   * This fires only the {@code $ld:ai:usage:create-judge} usage event. In v1.0 the SDK does not
   * auto-attach judges to completion or agent calls; evaluation is manual, driven by the returned
   * judge. Because the SDK ships no provider runners yet, the caller supplies the {@link Runner}.
   *
   * @param key the judge AI Config key
   * @param context the context to evaluate the configuration in
   * @param defaultValue the default used when the flag is absent or cannot be evaluated; when
   *     {@code null}, a disabled default is used
   * @param variables variables interpolated into the judge prompt; may be {@code null}
   * @param runner the runner the judge invokes; when {@code null}, no judge is created
   * @param sampleRate the default sampling rate for the judge in {@code [0.0, 1.0]}
   * @return a {@link Judge}, or {@code null} if the configuration is disabled or no runner was
   *     supplied
   */
  Judge createJudge(
      String key,
      LDContext context,
      AIJudgeConfigDefault defaultValue,
      Map<String, Object> variables,
      Runner runner,
      double sampleRate);
}
