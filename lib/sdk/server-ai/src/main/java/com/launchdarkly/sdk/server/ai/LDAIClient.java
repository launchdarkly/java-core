package com.launchdarkly.sdk.server.ai;

import com.launchdarkly.sdk.LDContext;

import java.util.Collections;
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
   * Retrieves a completion (chat/prompt) AI Config with Mustache placeholders left intact
   * (no interpolation). Useful for displaying prompt previews or storing templates for later
   * rendering.
   *
   * @param key the AI Config key
   * @param context the context to evaluate the configuration in
   * @param defaultValue the default returned when the flag is absent or cannot be evaluated; when
   *     {@code null}, a disabled default is used
   * @return the completion config with raw (non-interpolated) message content, never {@code null}
   */
  AICompletionConfig completionConfigTemplate(
      String key,
      LDContext context,
      AICompletionConfigDefault defaultValue);

  /**
   * Retrieves an agent AI Config with Mustache placeholders left intact (no interpolation). Useful
   * for auditing instruction templates or building UI previews.
   *
   * @param key the AI Config key
   * @param context the context to evaluate the configuration in
   * @param defaultValue the default returned when the flag is absent or cannot be evaluated; when
   *     {@code null}, a disabled default is used
   * @return the agent config with raw (non-interpolated) instructions, never {@code null}
   */
  AIAgentConfig agentConfigTemplate(
      String key,
      LDContext context,
      AIAgentConfigDefault defaultValue);

  /**
   * Retrieves a judge AI Config with Mustache placeholders left intact (no interpolation). Useful
   * for auditing judge prompt templates.
   *
   * @param key the AI Config key
   * @param context the context to evaluate the configuration in
   * @param defaultValue the default returned when the flag is absent or cannot be evaluated; when
   *     {@code null}, a disabled default is used
   * @return the judge config with raw (non-interpolated) message content, never {@code null}
   */
  AIJudgeConfig judgeConfigTemplate(
      String key,
      LDContext context,
      AIJudgeConfigDefault defaultValue);

  /**
   * Reconstructs a tracker from a resumption token, preserving the original run's identity.
   * <p>
   * Use this when a multi-turn or streaming AI interaction spans multiple requests. The caller
   * stores the resumption token from a previous tracker (via
   * {@link LDAIConfigTracker#getResumptionToken()}) and passes it back here to continue tracking
   * against the same run.
   * <p>
   * <strong>Security note:</strong> resumption tokens embed flag-evaluation details such as the
   * variation key and config version. Keep tokens server-side and do not round-trip them through
   * untrusted clients where they could leak flag-targeting information.
   *
   * @param resumptionToken the token returned by a previous tracker; must not be {@code null}
   * @param context the evaluation context for the new request; must not be {@code null}
   * @return a tracker with the decoded run identity, never {@code null}
   * @throws IllegalArgumentException if the token is malformed
   */
  LDAIConfigTracker createTracker(String resumptionToken, LDContext context);

  /**
   * Fetches and validates an agent graph definition identified by {@code graphKey}.
   * <p>
   * Evaluates the graph flag, fetches all referenced node configs, and validates the graph
   * structure. If validation fails (disabled flag, empty root, unreachable nodes, or any
   * non-enabled child config) the returned definition has {@link AgentGraphDefinition#isEnabled()}
   * {@code == false} and an empty node map.
   * <p>
   * Also emits a {@code $ld:ai:usage:agent-graph} usage event.
   *
   * @param graphKey the flag key identifying the agent graph
   * @param context the evaluation context
   * @param variables Mustache template variables applied to each node's instructions
   * @return the resolved graph definition; never {@code null}
   */
  AgentGraphDefinition agentGraph(String graphKey, LDContext context, Map<String, Object> variables);

  /**
   * Fetches and validates an agent graph definition with no template variables.
   *
   * @param graphKey the flag key identifying the agent graph
   * @param context the evaluation context
   * @return the resolved graph definition; never {@code null}
   */
  default AgentGraphDefinition agentGraph(String graphKey, LDContext context) {
    return agentGraph(graphKey, context, Collections.emptyMap());
  }

  /**
   * Reconstructs an {@link AIGraphTracker} from a resumption token.
   * <p>
   * Use this to continue tracking a graph run across requests by passing the token produced by
   * {@link AIGraphTracker#getResumptionToken()}.
   *
   * @param resumptionToken the token produced by a prior {@link AIGraphTracker}
   * @param context the evaluation context
   * @return a reconstructed tracker with the original run identity
   * @throws IllegalArgumentException if the token is malformed
   */
  AIGraphTracker createGraphTracker(String resumptionToken, LDContext context);
}
