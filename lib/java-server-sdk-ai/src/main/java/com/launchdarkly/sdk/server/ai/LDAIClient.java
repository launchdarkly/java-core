package com.launchdarkly.sdk.server.ai;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.LDValueType;
import com.launchdarkly.sdk.json.JsonSerialization;
import com.launchdarkly.sdk.server.ai.datamodel.AIAgentConfig;
import com.launchdarkly.sdk.server.ai.datamodel.AIAgentConfigDefault;
import com.launchdarkly.sdk.server.ai.datamodel.AIAgentConfigRequest;
import com.launchdarkly.sdk.server.ai.datamodel.AICompletionConfig;
import com.launchdarkly.sdk.server.ai.datamodel.AICompletionConfigDefault;
import com.launchdarkly.sdk.server.ai.datamodel.AIJudgeConfig;
import com.launchdarkly.sdk.server.ai.datamodel.AIJudgeConfigDefault;
import com.launchdarkly.sdk.server.ai.datamodel.JudgeConfiguration;
import com.launchdarkly.sdk.server.ai.datamodel.LDMessage;
import com.launchdarkly.sdk.server.ai.datamodel.LDTool;
import com.launchdarkly.sdk.server.ai.datamodel.ModelConfig;
import com.launchdarkly.sdk.server.ai.datamodel.ProviderConfig;
import com.launchdarkly.sdk.server.ai.evaluation.Evaluator;
import com.launchdarkly.sdk.server.ai.internal.AISdkInfo;
import com.launchdarkly.sdk.server.ai.internal.Interpolator;
import com.launchdarkly.sdk.server.ai.internal.LDValueConversions;
import com.launchdarkly.sdk.server.ai.tracking.AIConfigTracker;
import com.launchdarkly.sdk.server.interfaces.LDClientInterface;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The LaunchDarkly Server-Side AI SDK client.
 * <p>
 * {@code LDAIClient} is the gateway to AI Config functionality. It wraps a fully-configured
 * {@link LDClientInterface} (such as an {@code com.launchdarkly.sdk.server.LDClient}) and uses it to
 * evaluate AI Config flags, interpolate prompts, and record AI metrics.
 * <p>
 * Construct one instance per application, reusing your existing LaunchDarkly client:
 * <pre>{@code
 * LDClient ldClient = new LDClient(sdkKey);
 * LDAIClient aiClient = new LDAIClient(ldClient);
 *
 * AICompletionConfig config = aiClient.completionConfig(
 *     "my-ai-config",
 *     context,
 *     AICompletionConfigDefault.builder()
 *         .enabled(true)
 *         .model(new ModelConfig("gpt-4"))
 *         .messages(Arrays.asList(LDMessage.system("You are a helpful assistant named {{name}}.")))
 *         .build(),
 *     Collections.singletonMap("name", "Bailey"));
 *
 * if (config.isEnabled()) {
 *   AIConfigTracker tracker = config.createTracker();
 *   String answer = tracker.trackDurationOf(() -> callModel(config));
 *   tracker.trackSuccess();
 * }
 * }</pre>
 */
public final class LDAIClient {
  private static final String TRACK_SDK_INFO = "$ld:ai:sdk:info";
  private static final String TRACK_USAGE_COMPLETION_CONFIG = "$ld:ai:usage:completion-config";
  private static final String TRACK_USAGE_AGENT_CONFIG = "$ld:ai:usage:agent-config";
  private static final String TRACK_USAGE_AGENT_CONFIGS = "$ld:ai:usage:agent-configs";
  private static final String TRACK_USAGE_JUDGE_CONFIG = "$ld:ai:usage:judge-config";

  private static final LDContext INIT_TRACK_CONTEXT = LDContext.builder("ld-internal-tracking")
      .kind("ld_ai")
      .anonymous(true)
      .build();

  private final LDClientInterface client;
  private final LDLogger logger;

  /**
   * Creates an AI client wrapping the given LaunchDarkly client.
   * <p>
   * No assertion is made about the state of the supplied client; it is the caller's responsibility
   * to ensure it is properly configured and initialized before relying on AI Config functionality.
   * <p>
   * Construction emits a single {@code $ld:ai:sdk:info} event identifying this AI SDK and version.
   *
   * @param client a fully-configured LaunchDarkly client
   */
  public LDAIClient(LDClientInterface client) {
    this.client = client;
    this.logger = client.getLogger() != null ? client.getLogger() : LDLogger.none();

    client.trackMetric(
        TRACK_SDK_INFO,
        INIT_TRACK_CONTEXT,
        LDValue.buildObject()
            .put("aiSdkName", AISdkInfo.AI_SDK_NAME)
            .put("aiSdkVersion", AISdkInfo.AI_SDK_VERSION)
            .put("aiSdkLanguage", AISdkInfo.AI_SDK_LANGUAGE)
            .build(),
        1);
  }

  /**
   * Retrieves a completion ("traditional" chat-style) AI Config.
   *
   * @param key the configuration key
   * @param context the evaluation context
   * @param defaultValue the default value used when no flag variation is available; when
   *     {@code null}, a disabled default is used
   * @param variables variables for message interpolation, or {@code null}
   * @return the completion config, with a tracker factory for gathering metrics
   */
  public AICompletionConfig completionConfig(
      String key,
      LDContext context,
      AICompletionConfigDefault defaultValue,
      Map<String, ?> variables) {
    client.trackMetric(TRACK_USAGE_COMPLETION_CONFIG, context, LDValue.of(key), 1);

    AICompletionConfigDefault effectiveDefault =
        defaultValue != null ? defaultValue : AICompletionConfigDefault.disabled();
    Evaluation evaluation = evaluate(key, context, effectiveDefault.toLDValue(), variables, null);

    return AICompletionConfig.builder(key)
        .enabled(evaluation.enabled)
        .model(evaluation.model)
        .provider(evaluation.provider)
        .messages(evaluation.messages)
        .judgeConfiguration(evaluation.judgeConfiguration)
        .tools(resolveTools(evaluation.variation))
        .trackerFactory(evaluation.trackerFactory)
        .evaluator(Evaluator.noop())
        .build();
  }

  /**
   * Retrieves a single AI Config agent.
   *
   * @param key the agent configuration key
   * @param context the evaluation context
   * @param defaultValue the default value used when no flag variation is available; when
   *     {@code null}, a disabled default is used
   * @param variables variables for instruction interpolation, or {@code null}
   * @return the agent config, with a tracker factory for gathering metrics
   */
  public AIAgentConfig agentConfig(
      String key,
      LDContext context,
      AIAgentConfigDefault defaultValue,
      Map<String, ?> variables) {
    client.trackMetric(TRACK_USAGE_AGENT_CONFIG, context, LDValue.of(key), 1);
    return evaluateAgent(key, context, defaultValue != null ? defaultValue : AIAgentConfigDefault.disabled(),
        variables, null);
  }

  /**
   * Retrieves multiple AI Config agents in a single call.
   *
   * @param requests the agent requests, each with its own key, default, and variables
   * @param context the evaluation context
   * @return a map from agent key to its resolved {@link AIAgentConfig}
   */
  public Map<String, AIAgentConfig> agentConfigs(List<AIAgentConfigRequest> requests, LDContext context) {
    int agentCount = requests.size();
    client.trackMetric(TRACK_USAGE_AGENT_CONFIGS, context, LDValue.of(agentCount), agentCount);

    Map<String, AIAgentConfig> result = new LinkedHashMap<>();
    for (AIAgentConfigRequest request : requests) {
      AIAgentConfigDefault requestDefault =
          request.getDefaultValue() != null ? request.getDefaultValue() : AIAgentConfigDefault.disabled();
      AIAgentConfig agent = evaluateAgent(request.getKey(), context, requestDefault, request.getVariables(), null);
      result.put(request.getKey(), agent);
    }
    return result;
  }

  /**
   * Retrieves a judge AI Config used to evaluate AI outputs.
   *
   * @param key the judge configuration key
   * @param context the evaluation context
   * @param defaultValue the default value used when no flag variation is available; when
   *     {@code null}, a disabled default is used
   * @param variables variables for message interpolation, or {@code null}
   * @return the judge config, with a tracker factory for gathering metrics
   */
  public AIJudgeConfig judgeConfig(
      String key,
      LDContext context,
      AIJudgeConfigDefault defaultValue,
      Map<String, ?> variables) {
    client.trackMetric(TRACK_USAGE_JUDGE_CONFIG, context, LDValue.of(key), 1);

    AIJudgeConfigDefault effectiveDefault =
        defaultValue != null ? defaultValue : AIJudgeConfigDefault.disabled();
    Evaluation evaluation = evaluate(key, context, effectiveDefault.toLDValue(), variables, null);

    String evaluationMetricKey = extractEvaluationMetricKey(evaluation.variation);

    return AIJudgeConfig.builder(key)
        .enabled(evaluation.enabled)
        .model(evaluation.model)
        .provider(evaluation.provider)
        .messages(evaluation.messages)
        .evaluationMetricKey(evaluationMetricKey)
        .trackerFactory(evaluation.trackerFactory)
        .build();
  }

  /**
   * Reconstructs an {@link AIConfigTracker} from a resumption token previously produced by
   * {@link AIConfigTracker#getResumptionToken()}.
   * <p>
   * This is the primary mechanism for associating deferred events -- such as user feedback -- with
   * the specific config version and invocation that produced the original response.
   *
   * @param token the resumption token
   * @param context the context to use for subsequent track calls
   * @return a tracker bound to the original run
   * @throws IllegalArgumentException if the token is invalid or missing a required field
   */
  public AIConfigTracker createTracker(String token, LDContext context) {
    return AIConfigTracker.fromResumptionToken(token, client, context);
  }

  private AIAgentConfig evaluateAgent(
      String key,
      LDContext context,
      AIAgentConfigDefault agentDefault,
      Map<String, ?> variables,
      String graphKey) {
    Evaluation evaluation = evaluate(key, context, agentDefault.toLDValue(), variables, graphKey);

    String instructions = evaluation.instructions != null ? evaluation.instructions : agentDefault.getInstructions();

    return AIAgentConfig.builder(key)
        .enabled(evaluation.enabled)
        .model(evaluation.model != null ? evaluation.model : agentDefault.getModel())
        .provider(evaluation.provider != null ? evaluation.provider : agentDefault.getProvider())
        .instructions(instructions)
        .judgeConfiguration(evaluation.judgeConfiguration)
        .tools(resolveTools(evaluation.variation))
        .trackerFactory(evaluation.trackerFactory)
        .evaluator(Evaluator.noop())
        .build();
  }

  private Evaluation evaluate(
      String key,
      LDContext context,
      LDValue defaultValue,
      Map<String, ?> variables,
      String graphKey) {
    LDValue variation = client.jsonValueVariation(key, context, defaultValue);
    if (variation.getType() != LDValueType.OBJECT) {
      logger.error("AI Config '{}' did not return a JSON object; falling back to the provided default.", key);
      variation = defaultValue;
    }

    Map<String, Object> allVariables = buildVariables(context, variables);

    List<LDMessage> messages = parseMessages(variation.get("messages"), allVariables);
    String instructions = parseInstructions(variation.get("instructions"), allVariables);
    ProviderConfig provider = parseProvider(variation.get("provider"));
    ModelConfig model = parseModel(variation.get("model"));
    JudgeConfiguration judgeConfiguration = parseJudgeConfiguration(variation.get("judgeConfiguration"));

    LDValue ldMeta = variation.get("_ldMeta");
    String variationKey = ldMeta.get("variationKey").isNull() ? "" : ldMeta.get("variationKey").stringValue();
    int version = ldMeta.get("version").isNull() ? 1 : ldMeta.get("version").intValue();
    boolean enabled = ldMeta.get("enabled").booleanValue();

    String modelName = model != null ? model.getName() : "";
    String providerName = provider != null ? provider.getName() : "";

    Supplier<AIConfigTracker> trackerFactory = () -> AIConfigTracker.builder(client)
        .logger(logger)
        .runId(UUID.randomUUID().toString())
        .configKey(key)
        .variationKey(variationKey)
        .version(version)
        .context(context)
        .modelName(modelName)
        .providerName(providerName)
        .graphKey(graphKey)
        .build();

    Evaluation evaluation = new Evaluation();
    evaluation.model = model;
    evaluation.provider = provider;
    evaluation.messages = messages;
    evaluation.instructions = instructions;
    evaluation.trackerFactory = trackerFactory;
    evaluation.enabled = enabled;
    evaluation.judgeConfiguration = judgeConfiguration;
    evaluation.variation = variation;
    return evaluation;
  }

  private Map<String, Object> buildVariables(LDContext context, Map<String, ?> variables) {
    Map<String, Object> allVariables = new LinkedHashMap<>();
    if (variables != null) {
      for (Map.Entry<String, ?> entry : variables.entrySet()) {
        allVariables.put(entry.getKey(), normalizeVariable(entry.getValue()));
      }
    }
    // The ldctx entry is always added last so it overrides any caller-supplied "ldctx" key.
    allVariables.put("ldctx", contextToPlainObject(context));
    return allVariables;
  }

  private Object normalizeVariable(Object value) {
    if (value instanceof LDValue) {
      return LDValueConversions.toPlainObject((LDValue) value);
    }
    return value;
  }

  private Object contextToPlainObject(LDContext context) {
    return LDValueConversions.toPlainObject(LDValue.parse(JsonSerialization.serialize(context)));
  }

  private List<LDMessage> parseMessages(LDValue messagesValue, Map<String, Object> variables) {
    if (messagesValue.getType() != LDValueType.ARRAY) {
      return null;
    }
    for (LDValue entry : messagesValue.values()) {
      if (entry.getType() != LDValueType.OBJECT) {
        return null;
      }
    }
    List<LDMessage> messages = new ArrayList<>(messagesValue.size());
    for (LDValue entry : messagesValue.values()) {
      String role = entry.get("role").stringValue();
      String content = Interpolator.interpolate(entry.get("content").stringValue(), variables);
      messages.add(new LDMessage(role, content));
    }
    return messages;
  }

  private String parseInstructions(LDValue instructionsValue, Map<String, Object> variables) {
    if (instructionsValue.getType() != LDValueType.STRING) {
      return null;
    }
    return Interpolator.interpolate(instructionsValue.stringValue(), variables);
  }

  private ProviderConfig parseProvider(LDValue providerValue) {
    if (providerValue.getType() != LDValueType.OBJECT) {
      return null;
    }
    return new ProviderConfig(providerValue.get("name").isNull() ? "" : providerValue.get("name").stringValue());
  }

  private ModelConfig parseModel(LDValue modelValue) {
    if (modelValue.getType() != LDValueType.OBJECT) {
      return null;
    }
    String name = modelValue.get("name").isNull() ? "" : modelValue.get("name").stringValue();
    LDValue parameters = modelValue.get("parameters");
    LDValue custom = modelValue.get("custom");
    return new ModelConfig(name, parameters, custom);
  }

  private JudgeConfiguration parseJudgeConfiguration(LDValue judgeConfigValue) {
    if (judgeConfigValue.getType() != LDValueType.OBJECT) {
      return null;
    }
    LDValue judgesValue = judgeConfigValue.get("judges");
    if (judgesValue.getType() != LDValueType.ARRAY) {
      return null;
    }
    List<JudgeConfiguration.Judge> judges = new ArrayList<>();
    for (LDValue judge : judgesValue.values()) {
      if (judge.getType() == LDValueType.OBJECT
          && !judge.get("key").isNull()
          && !judge.get("samplingRate").isNull()) {
        judges.add(new JudgeConfiguration.Judge(
            judge.get("key").stringValue(),
            judge.get("samplingRate").doubleValue()));
      }
    }
    return judges.isEmpty() ? null : new JudgeConfiguration(judges);
  }

  private String extractEvaluationMetricKey(LDValue variation) {
    LDValue metricKey = variation.get("evaluationMetricKey");
    if (!metricKey.isNull()) {
      return metricKey.stringValue();
    }
    LDValue metricKeys = variation.get("evaluationMetricKeys");
    if (metricKeys.getType() == LDValueType.ARRAY && metricKeys.size() > 0) {
      return metricKeys.get(0).stringValue();
    }
    return null;
  }

  private Map<String, LDTool> resolveTools(LDValue variation) {
    LDValue toolsValue = variation.get("tools");
    if (!toolsValue.isNull()) {
      if (toolsValue.getType() != LDValueType.OBJECT) {
        return null;
      }
      Map<String, LDTool> tools = new LinkedHashMap<>();
      for (String toolName : toolsValue.keys()) {
        LDValue toolValue = toolsValue.get(toolName);
        if (toolValue.getType() == LDValueType.OBJECT) {
          tools.put(toolName, toolFromValue(
              toolValue.get("name").isNull() ? toolName : toolValue.get("name").stringValue(),
              toolValue));
        } else {
          logger.warn("Skipping tool '{}': expected an object", toolName);
        }
      }
      return tools.isEmpty() ? null : tools;
    }

    LDValue model = variation.get("model");
    if (model.getType() != LDValueType.OBJECT) {
      return null;
    }
    LDValue parameters = model.get("parameters");
    if (parameters.getType() != LDValueType.OBJECT) {
      return null;
    }
    LDValue toolsList = parameters.get("tools");
    if (toolsList.getType() != LDValueType.ARRAY) {
      return null;
    }
    Map<String, LDTool> tools = new LinkedHashMap<>();
    for (LDValue item : toolsList.values()) {
      if (item.getType() != LDValueType.OBJECT) {
        logger.warn("Skipping tool entry: expected an object");
        continue;
      }
      if (item.get("name").isNull() || item.get("name").stringValue().isEmpty()) {
        logger.warn("Skipping tool entry: missing name");
        continue;
      }
      String toolName = item.get("name").stringValue();
      tools.put(toolName, toolFromValue(toolName, item));
    }
    return tools.isEmpty() ? null : tools;
  }

  private LDTool toolFromValue(String name, LDValue value) {
    LDTool.Builder builder = LDTool.builder(name);
    if (!value.get("description").isNull()) {
      builder.description(value.get("description").stringValue());
    }
    if (!value.get("type").isNull()) {
      builder.type(value.get("type").stringValue());
    }
    if (!value.get("parameters").isNull()) {
      builder.parameters(value.get("parameters"));
    }
    if (!value.get("customParameters").isNull()) {
      builder.customParameters(value.get("customParameters"));
    }
    return builder.build();
  }

  /**
   * Internal carrier for the components extracted from a flag variation.
   */
  private static final class Evaluation {
    private ModelConfig model;
    private ProviderConfig provider;
    private List<LDMessage> messages;
    private String instructions;
    private Supplier<AIConfigTracker> trackerFactory;
    private boolean enabled;
    private JudgeConfiguration judgeConfiguration;
    private LDValue variation;
  }
}
