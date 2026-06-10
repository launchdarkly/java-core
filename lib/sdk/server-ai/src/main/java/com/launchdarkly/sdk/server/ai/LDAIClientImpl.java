package com.launchdarkly.sdk.server.ai;

import com.launchdarkly.logging.LDLogAdapter;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LDSLF4J;
import com.launchdarkly.logging.Logs;
import com.launchdarkly.sdk.ContextKind;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.LDValueType;
import com.launchdarkly.sdk.server.ai.datamodel.AIConfigMode;
import com.launchdarkly.sdk.server.ai.datamodel.LDMessage;
import com.launchdarkly.sdk.server.ai.internal.AIConfigFlagValue;
import com.launchdarkly.sdk.server.ai.internal.AIConfigParser;
import com.launchdarkly.sdk.server.ai.internal.AISdkInfo;
import com.launchdarkly.sdk.server.ai.internal.Interpolator;
import com.launchdarkly.sdk.server.ai.internal.NoOpAIConfigTracker;
import com.launchdarkly.sdk.server.interfaces.LDClientInterface;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The default {@link LDAIClient} implementation, backed by an initialized server-side
 * {@code LDClient}.
 * <p>
 * Construct one per application alongside your {@code LDClient}:
 * <pre>{@code
 * LDClient ldClient = new LDClient(sdkKey);
 * LDAIClient aiClient = new LDAIClientImpl(ldClient);
 * }</pre>
 * <p>
 * This class is thread-safe. It holds only the immutable, thread-safe base client, a logger, and a
 * single shared {@link Interpolator} (whose compiled-template cache is itself thread-safe); every
 * config it returns is immutable.
 */
public final class LDAIClientImpl implements LDAIClient {
  private static final String TRACK_SDK_INFO = "$ld:ai:sdk:info";
  private static final String TRACK_USAGE_COMPLETION_CONFIG = "$ld:ai:usage:completion-config";
  private static final String TRACK_USAGE_AGENT_CONFIG = "$ld:ai:usage:agent-config";
  private static final String TRACK_USAGE_AGENT_CONFIGS = "$ld:ai:usage:agent-configs";
  private static final String TRACK_USAGE_JUDGE_CONFIG = "$ld:ai:usage:judge-config";

  private static final LDContext INIT_TRACK_CONTEXT = LDContext
      .builder("ld-internal-tracking")
      .kind(ContextKind.of("ld_ai"))
      .anonymous(true)
      .build();

  // Tracking is implemented in a later step; until then every config hands out the no-op tracker.
  private static final Supplier<LDAIConfigTracker> TRACKER_FACTORY = () -> NoOpAIConfigTracker.INSTANCE;

  private final LDClientInterface client;
  private final LDLogger logger;
  private final Interpolator interpolator;

  /**
   * Creates an AI client wrapping the given base client, using a default logger.
   *
   * @param client an initialized server-side {@code LDClient}; must not be {@code null}
   */
  public LDAIClientImpl(LDClientInterface client) {
    this(client, defaultLogger());
  }

  /**
   * Creates an AI client wrapping the given base client and logging through the given logger.
   *
   * @param client an initialized server-side {@code LDClient}; must not be {@code null}
   * @param logger the logger to use for warnings; must not be {@code null}
   */
  public LDAIClientImpl(LDClientInterface client, LDLogger logger) {
    this.client = Objects.requireNonNull(client, "client");
    this.logger = Objects.requireNonNull(logger, "logger");
    this.interpolator = new Interpolator();

    // Report SDK info once. Guard it: if the base client is not yet fully initialized, a track call
    // must never propagate an exception out of this constructor.
    try {
      LDValue info = LDValue.buildObject()
          .put("aiSdkName", AISdkInfo.NAME)
          .put("aiSdkVersion", AISdkInfo.VERSION)
          .put("aiSdkLanguage", AISdkInfo.LANGUAGE)
          .build();
      client.trackMetric(TRACK_SDK_INFO, INIT_TRACK_CONTEXT, info, 1);
    } catch (Exception e) {
      this.logger.warn("Unable to record AI SDK info event: {}", e.toString());
    }
  }

  @Override
  public AICompletionConfig completionConfig(
      String key,
      LDContext context,
      AICompletionConfigDefault defaultValue,
      Map<String, Object> variables) {
    client.trackMetric(TRACK_USAGE_COMPLETION_CONFIG, context, LDValue.of(key), 1);
    AICompletionConfigDefault effectiveDefault =
        defaultValue != null ? defaultValue : AICompletionConfigDefault.disabled();
    return (AICompletionConfig) evaluate(key, context, effectiveDefault, AIConfigMode.COMPLETION, variables);
  }

  @Override
  public AIAgentConfig agentConfig(
      String key,
      LDContext context,
      AIAgentConfigDefault defaultValue,
      Map<String, Object> variables) {
    client.trackMetric(TRACK_USAGE_AGENT_CONFIG, context, LDValue.of(key), 1);
    return evaluateAgent(key, context, defaultValue, variables);
  }

  @Override
  public Map<String, AIAgentConfig> agentConfigs(
      List<AIAgentConfigRequest> agentConfigs, LDContext context) {
    int count = agentConfigs == null ? 0 : agentConfigs.size();
    client.trackMetric(TRACK_USAGE_AGENT_CONFIGS, context, LDValue.of(count), count);

    Map<String, AIAgentConfig> result = new LinkedHashMap<>();
    if (agentConfigs != null) {
      for (AIAgentConfigRequest request : agentConfigs) {
        if (request == null) {
          continue;
        }
        result.put(
            request.getKey(),
            evaluateAgent(request.getKey(), context, request.getDefaultValue(), request.getVariables()));
      }
    }
    return result;
  }

  @Override
  public AIJudgeConfig judgeConfig(
      String key,
      LDContext context,
      AIJudgeConfigDefault defaultValue,
      Map<String, Object> variables) {
    client.trackMetric(TRACK_USAGE_JUDGE_CONFIG, context, LDValue.of(key), 1);
    AIJudgeConfigDefault effectiveDefault =
        defaultValue != null ? defaultValue : AIJudgeConfigDefault.disabled();
    return (AIJudgeConfig) evaluate(key, context, effectiveDefault, AIConfigMode.JUDGE, variables);
  }

  private AIAgentConfig evaluateAgent(
      String key, LDContext context, AIAgentConfigDefault defaultValue, Map<String, Object> variables) {
    AIAgentConfigDefault effectiveDefault =
        defaultValue != null ? defaultValue : AIAgentConfigDefault.disabled();
    return (AIAgentConfig) evaluate(key, context, effectiveDefault, AIConfigMode.AGENT, variables);
  }

  /**
   * Core evaluation: evaluate the flag with a null sentinel default, validate the mode, and build
   * the typed config with interpolated prompt content. When the flag is absent or cannot be
   * evaluated, the caller's typed default is returned directly (no JSON round-trip).
   */
  private AIConfig evaluate(
      String key,
      LDContext context,
      AIConfigDefault defaultValue,
      AIConfigMode mode,
      Map<String, Object> variables) {
    LDValue value = client.jsonValueVariation(key, context, LDValue.ofNull());

    // A valid AI Config variation is always a JSON object (it carries the _ldMeta block). When the
    // flag is absent or cannot be evaluated the base SDK hands back our null sentinel; in that case
    // we return the caller's typed default directly rather than serializing it and parsing it back.
    if (value == null || value.getType() != LDValueType.OBJECT) {
      return buildConfigFromDefault(key, mode, defaultValue, context, variables);
    }

    AIConfigFlagValue parsed = AIConfigParser.parse(value);

    AIConfigMode flagMode = parsed.getMode() != null ? parsed.getMode() : AIConfigMode.COMPLETION;
    if (flagMode != mode) {
      logger.warn(
          "AI Config mode mismatch for {}: expected {}, got {}. Returning disabled config.",
          key, mode.getWireValue(), flagMode.getWireValue());
      return disabledConfig(key, mode);
    }

    return buildConfig(key, mode, parsed, context, variables);
  }

  private AIConfig buildConfig(
      String key,
      AIConfigMode mode,
      AIConfigFlagValue parsed,
      LDContext context,
      Map<String, Object> variables) {
    switch (mode) {
      case AGENT:
        return new AIAgentConfig(
            key,
            parsed.isEnabled(),
            parsed.getModel(),
            parsed.getProvider(),
            interpolate(parsed.getInstructions(), variables, context),
            parsed.getJudgeConfiguration(),
            parsed.getTools(),
            TRACKER_FACTORY);
      case JUDGE:
        return new AIJudgeConfig(
            key,
            parsed.isEnabled(),
            parsed.getModel(),
            parsed.getProvider(),
            interpolateMessages(parsed.getMessages(), variables, context),
            parsed.getEvaluationMetricKey(),
            TRACKER_FACTORY);
      case COMPLETION:
      default:
        return new AICompletionConfig(
            key,
            parsed.isEnabled(),
            parsed.getModel(),
            parsed.getProvider(),
            interpolateMessages(parsed.getMessages(), variables, context),
            parsed.getJudgeConfiguration(),
            parsed.getTools(),
            TRACKER_FACTORY);
    }
  }

  /**
   * Builds the typed config straight from the caller-supplied default, used when the flag is absent
   * or cannot be evaluated. Prompt content is interpolated exactly as it is for an evaluated flag.
   */
  private AIConfig buildConfigFromDefault(
      String key,
      AIConfigMode mode,
      AIConfigDefault defaultValue,
      LDContext context,
      Map<String, Object> variables) {
    switch (mode) {
      case AGENT: {
        AIAgentConfigDefault agent = (AIAgentConfigDefault) defaultValue;
        return new AIAgentConfig(
            key,
            agent.isEnabled(),
            agent.getModel(),
            agent.getProvider(),
            interpolate(agent.getInstructions(), variables, context),
            agent.getJudgeConfiguration(),
            agent.getTools(),
            TRACKER_FACTORY);
      }
      case JUDGE: {
        AIJudgeConfigDefault judge = (AIJudgeConfigDefault) defaultValue;
        return new AIJudgeConfig(
            key,
            judge.isEnabled(),
            judge.getModel(),
            judge.getProvider(),
            interpolateMessages(judge.getMessages(), variables, context),
            judge.getEvaluationMetricKey(),
            TRACKER_FACTORY);
      }
      case COMPLETION:
      default: {
        AICompletionConfigDefault completion = (AICompletionConfigDefault) defaultValue;
        return new AICompletionConfig(
            key,
            completion.isEnabled(),
            completion.getModel(),
            completion.getProvider(),
            interpolateMessages(completion.getMessages(), variables, context),
            completion.getJudgeConfiguration(),
            completion.getTools(),
            TRACKER_FACTORY);
      }
    }
  }

  private AIConfig disabledConfig(String key, AIConfigMode mode) {
    switch (mode) {
      case AGENT:
        return new AIAgentConfig(key, false, null, null, null, null, null, TRACKER_FACTORY);
      case JUDGE:
        return new AIJudgeConfig(key, false, null, null, null, null, TRACKER_FACTORY);
      case COMPLETION:
      default:
        return new AICompletionConfig(key, false, null, null, null, null, null, TRACKER_FACTORY);
    }
  }

  private List<LDMessage> interpolateMessages(
      List<LDMessage> messages, Map<String, Object> variables, LDContext context) {
    if (messages == null) {
      return null;
    }
    List<LDMessage> result = new ArrayList<>(messages.size());
    for (LDMessage message : messages) {
      result.add(message.withContent(interpolator.interpolate(message.getContent(), variables, context)));
    }
    return result;
  }

  private String interpolate(String template, Map<String, Object> variables, LDContext context) {
    return interpolator.interpolate(template, variables, context);
  }

  private static LDLogger defaultLogger() {
    LDLogAdapter adapter;
    try {
      Class.forName("org.slf4j.LoggerFactory");
      adapter = LDSLF4J.adapter();
    } catch (ClassNotFoundException e) {
      adapter = Logs.toConsole();
    }
    return LDLogger.withAdapter(adapter, "LaunchDarkly.AI");
  }
}
