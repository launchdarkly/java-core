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
}
