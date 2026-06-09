package com.launchdarkly.sdk.server.ai;

import com.launchdarkly.logging.LDLogAdapter;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LDSLF4J;
import com.launchdarkly.logging.Logs;
import com.launchdarkly.sdk.ArrayBuilder;
import com.launchdarkly.sdk.ContextKind;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.ObjectBuilder;
import com.launchdarkly.sdk.server.ai.datamodel.AIConfigMode;
import com.launchdarkly.sdk.server.ai.datamodel.JudgeConfiguration;
import com.launchdarkly.sdk.server.ai.datamodel.LDMessage;
import com.launchdarkly.sdk.server.ai.datamodel.ModelConfig;
import com.launchdarkly.sdk.server.ai.datamodel.ProviderConfig;
import com.launchdarkly.sdk.server.ai.datamodel.ToolConfig;
import com.launchdarkly.sdk.server.ai.internal.AIConfigFlagValue;
import com.launchdarkly.sdk.server.ai.internal.AIConfigParser;
import com.launchdarkly.sdk.server.ai.internal.AISdkInfo;
import com.launchdarkly.sdk.server.ai.internal.Interpolator;
import com.launchdarkly.sdk.server.ai.internal.LDValueConverter;
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
   * Core evaluation: render the default as a flag value (so the base SDK returns it verbatim when
   * the flag is absent), evaluate, validate the mode, and build the typed config with interpolated
   * prompt content.
   */
  private AIConfig evaluate(
      String key,
      LDContext context,
      AIConfigDefault defaultValue,
      AIConfigMode mode,
      Map<String, Object> variables) {
    LDValue defaultFlagValue = toFlagValue(defaultValue, mode);
    LDValue value = client.jsonValueVariation(key, context, defaultFlagValue);
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

  // ---------------------------------------------------------------------------
  // Default -> flag value rendering (inverse of AIConfigParser). Kept in sync with the field names
  // the parser reads so a default round-trips back to an equivalent config.
  // ---------------------------------------------------------------------------

  private static LDValue toFlagValue(AIConfigDefault config, AIConfigMode mode) {
    ObjectBuilder builder = LDValue.buildObject();
    builder.put("_ldMeta", LDValue.buildObject()
        .put("enabled", config.isEnabled())
        .put("mode", mode.getWireValue())
        .build());

    if (config.getModel() != null) {
      builder.put("model", modelToLdValue(config.getModel()));
    }
    if (config.getProvider() != null) {
      builder.put("provider", providerToLdValue(config.getProvider()));
    }

    if (config instanceof AICompletionConfigDefault) {
      AICompletionConfigDefault completion = (AICompletionConfigDefault) config;
      putMessages(builder, completion.getMessages());
      putJudgeConfiguration(builder, completion.getJudgeConfiguration());
      putTools(builder, completion.getTools());
    } else if (config instanceof AIAgentConfigDefault) {
      AIAgentConfigDefault agent = (AIAgentConfigDefault) config;
      if (agent.getInstructions() != null) {
        builder.put("instructions", agent.getInstructions());
      }
      putJudgeConfiguration(builder, agent.getJudgeConfiguration());
      putTools(builder, agent.getTools());
    } else if (config instanceof AIJudgeConfigDefault) {
      AIJudgeConfigDefault judge = (AIJudgeConfigDefault) config;
      putMessages(builder, judge.getMessages());
      if (judge.getEvaluationMetricKey() != null) {
        builder.put("evaluationMetricKey", judge.getEvaluationMetricKey());
      }
    }

    return builder.build();
  }

  private static LDValue modelToLdValue(ModelConfig model) {
    ObjectBuilder builder = LDValue.buildObject();
    if (model.getName() != null) {
      builder.put("name", model.getName());
    }
    if (!model.getParameters().isEmpty()) {
      builder.put("parameters", LDValueConverter.fromJavaObject(model.getParameters()));
    }
    if (!model.getCustom().isEmpty()) {
      builder.put("custom", LDValueConverter.fromJavaObject(model.getCustom()));
    }
    return builder.build();
  }

  private static LDValue providerToLdValue(ProviderConfig provider) {
    ObjectBuilder builder = LDValue.buildObject();
    if (provider.getName() != null) {
      builder.put("name", provider.getName());
    }
    return builder.build();
  }

  private static void putMessages(ObjectBuilder builder, List<LDMessage> messages) {
    if (messages == null) {
      return;
    }
    ArrayBuilder array = LDValue.buildArray();
    for (LDMessage message : messages) {
      array.add(LDValue.buildObject()
          .put("role", message.getRole().getWireValue())
          .put("content", message.getContent())
          .build());
    }
    builder.put("messages", array.build());
  }

  private static void putJudgeConfiguration(ObjectBuilder builder, JudgeConfiguration judgeConfiguration) {
    if (judgeConfiguration == null) {
      return;
    }
    ArrayBuilder judges = LDValue.buildArray();
    for (JudgeConfiguration.Judge judge : judgeConfiguration.getJudges()) {
      judges.add(LDValue.buildObject()
          .put("key", judge.getKey())
          .put("samplingRate", judge.getSamplingRate())
          .build());
    }
    builder.put("judgeConfiguration", LDValue.buildObject().put("judges", judges.build()).build());
  }

  private static void putTools(ObjectBuilder builder, Map<String, ToolConfig> tools) {
    if (tools == null) {
      return;
    }
    ObjectBuilder toolsObject = LDValue.buildObject();
    for (Map.Entry<String, ToolConfig> entry : tools.entrySet()) {
      ToolConfig tool = entry.getValue();
      ObjectBuilder toolObject = LDValue.buildObject();
      if (tool.getName() != null) {
        toolObject.put("name", tool.getName());
      }
      if (tool.getDescription() != null) {
        toolObject.put("description", tool.getDescription());
      }
      if (tool.getType() != null) {
        toolObject.put("type", tool.getType());
      }
      if (!tool.getParameters().isEmpty()) {
        toolObject.put("parameters", LDValueConverter.fromJavaObject(tool.getParameters()));
      }
      if (!tool.getCustomParameters().isEmpty()) {
        toolObject.put("customParameters", LDValueConverter.fromJavaObject(tool.getCustomParameters()));
      }
      toolsObject.put(entry.getKey(), toolObject.build());
    }
    builder.put("tools", toolsObject.build());
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
