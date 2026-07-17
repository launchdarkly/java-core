package com.launchdarkly.sdk.server.ai;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.ContextKind;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.LDValueType;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Message;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Mode;
import com.launchdarkly.sdk.server.ai.internal.AgentGraphFlagValue;
import com.launchdarkly.sdk.server.ai.internal.AIConfigFlagValue;
import com.launchdarkly.sdk.server.ai.internal.AIConfigParser;
import com.launchdarkly.sdk.server.ai.internal.AISdkInfo;
import com.launchdarkly.sdk.server.ai.internal.Interpolator;
import com.launchdarkly.sdk.server.ai.internal.LDAIConfigTrackerImpl;
import com.launchdarkly.sdk.server.ai.internal.Loggers;
import com.launchdarkly.sdk.server.interfaces.LDClientInterface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
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
  private static final String TRACK_USAGE_COMPLETION_CONFIG_TEMPLATE = "$ld:ai:usage:completion-config-template";
  private static final String TRACK_USAGE_AGENT_CONFIG_TEMPLATE = "$ld:ai:usage:agent-config-template";
  private static final String TRACK_USAGE_JUDGE_CONFIG_TEMPLATE = "$ld:ai:usage:judge-config-template";
  private static final String TRACK_USAGE_AGENT_GRAPH = "$ld:ai:usage:agent-graph";

  private static final LDContext INIT_TRACK_CONTEXT = LDContext
      .builder("ld-internal-tracking")
      .kind(ContextKind.of("ld_ai"))
      .anonymous(true)
      .build();


  private final LDClientInterface client;
  private final LDLogger logger;
  private final Interpolator interpolator;

  /**
   * Creates an AI client wrapping the given base client, using a default logger.
   *
   * @param client an initialized server-side {@code LDClient}; must not be {@code null}
   */
  public LDAIClientImpl(LDClientInterface client) {
    this(client, Loggers.defaultLogger());
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

    LDValue info = LDValue.buildObject()
        .put("aiSdkName", AISdkInfo.NAME)
        .put("aiSdkVersion", AISdkInfo.VERSION)
        .put("aiSdkLanguage", AISdkInfo.LANGUAGE)
        .build();
    client.trackMetric(TRACK_SDK_INFO, INIT_TRACK_CONTEXT, info, 1);
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
    return (AICompletionConfig) evaluate(key, context, effectiveDefault, Mode.COMPLETION, variables, true);
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
    int count = 0;
    if (agentConfigs != null) {
      for (AIAgentConfigRequest request : agentConfigs) {
        if (request != null) {
          count++;
        }
      }
    }
    client.trackMetric(TRACK_USAGE_AGENT_CONFIGS, context, LDValue.of(count), count);

    Map<String, AIAgentConfig> result = new LinkedHashMap<>();
    if (agentConfigs != null) {
      for (AIAgentConfigRequest request : agentConfigs) {
        if (request != null) {
          result.put(
              request.getKey(),
              evaluateAgent(request.getKey(), context, request.getDefaultValue(), request.getVariables()));
        }
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
    return (AIJudgeConfig) evaluate(key, context, effectiveDefault, Mode.JUDGE, variables, true);
  }

  @Override
  public AICompletionConfig completionConfigTemplate(
      String key,
      LDContext context,
      AICompletionConfigDefault defaultValue) {
    client.trackMetric(TRACK_USAGE_COMPLETION_CONFIG_TEMPLATE, context, LDValue.of(key), 1);
    AICompletionConfigDefault effectiveDefault =
        defaultValue != null ? defaultValue : AICompletionConfigDefault.disabled();
    return (AICompletionConfig) evaluate(key, context, effectiveDefault, Mode.COMPLETION, null, false);
  }

  @Override
  public AIAgentConfig agentConfigTemplate(
      String key,
      LDContext context,
      AIAgentConfigDefault defaultValue) {
    client.trackMetric(TRACK_USAGE_AGENT_CONFIG_TEMPLATE, context, LDValue.of(key), 1);
    AIAgentConfigDefault effectiveDefault =
        defaultValue != null ? defaultValue : AIAgentConfigDefault.disabled();
    return (AIAgentConfig) evaluate(key, context, effectiveDefault, Mode.AGENT, null, false);
  }

  @Override
  public AIJudgeConfig judgeConfigTemplate(
      String key,
      LDContext context,
      AIJudgeConfigDefault defaultValue) {
    client.trackMetric(TRACK_USAGE_JUDGE_CONFIG_TEMPLATE, context, LDValue.of(key), 1);
    AIJudgeConfigDefault effectiveDefault =
        defaultValue != null ? defaultValue : AIJudgeConfigDefault.disabled();
    return (AIJudgeConfig) evaluate(key, context, effectiveDefault, Mode.JUDGE, null, false);
  }

  private AIAgentConfig evaluateAgent(
      String key, LDContext context, AIAgentConfigDefault defaultValue, Map<String, Object> variables) {
    return evaluateAgent(key, context, defaultValue, variables, null);
  }

  private AIAgentConfig evaluateAgent(
      String key, LDContext context, AIAgentConfigDefault defaultValue,
      Map<String, Object> variables, String graphKey) {
    AIAgentConfigDefault effectiveDefault =
        defaultValue != null ? defaultValue : AIAgentConfigDefault.disabled();
    return (AIAgentConfig) evaluate(key, context, effectiveDefault, Mode.AGENT, variables, true, graphKey);
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
      Mode mode,
      Map<String, Object> variables,
      boolean interpolate) {
    return evaluate(key, context, defaultValue, mode, variables, interpolate, null);
  }

  private AIConfig evaluate(
      String key,
      LDContext context,
      AIConfigDefault defaultValue,
      Mode mode,
      Map<String, Object> variables,
      boolean interpolate,
      String graphKey) {
    LDValue value = client.jsonValueVariation(key, context, LDValue.ofNull());

    // A valid AI Config variation is always a JSON object (it carries the _ldMeta block). When the
    // flag is absent or cannot be evaluated the base SDK hands back our null sentinel; in that case
    // we return the caller's typed default directly rather than serializing it and parsing it back.
    if (value == null || value.getType() != LDValueType.OBJECT) {
      return buildConfigFromDefault(key, mode, defaultValue, context, variables, interpolate, graphKey);
    }

    AIConfigFlagValue parsed = AIConfigParser.parse(value);

    Mode flagMode = parsed.getMode() != null ? parsed.getMode() : Mode.COMPLETION;
    if (flagMode != mode) {
      logger.warn(
          "AI Config mode mismatch for {}: expected {}, got {}. Returning default config.",
          key, mode.getWireValue(), flagMode.getWireValue());
      return buildConfigFromDefault(key, mode, defaultValue, context, variables, interpolate, graphKey);
    }

    return buildConfig(key, mode, parsed, context, variables, interpolate, graphKey);
  }

  private AIConfig buildConfig(
      String key,
      Mode mode,
      AIConfigFlagValue parsed,
      LDContext context,
      Map<String, Object> variables,
      boolean interpolate) {
    return buildConfig(key, mode, parsed, context, variables, interpolate, null);
  }

  private AIConfig buildConfig(
      String key,
      Mode mode,
      AIConfigFlagValue parsed,
      LDContext context,
      Map<String, Object> variables,
      boolean interpolate,
      String graphKey) {
    Supplier<LDAIConfigTracker> factory = trackerFactory(
        key, parsed.getVariationKey(), parsed.getVersion(),
        parsed.getModel(), parsed.getProvider(),
        parsed.getModelKey(), parsed.getModelVersion(), context, graphKey);
    switch (mode) {
      case AGENT:
        return new AIAgentConfig(
            key,
            parsed.isEnabled(),
            parsed.getModel(),
            parsed.getProvider(),
            interpolate ? interpolate(parsed.getInstructions(), variables, context)
                        : parsed.getInstructions(),
            parsed.getJudgeConfiguration(),
            parsed.getTools(),
            factory,
            Evaluator.noop());
      case JUDGE:
        return new AIJudgeConfig(
            key,
            parsed.isEnabled(),
            parsed.getModel(),
            parsed.getProvider(),
            interpolate ? interpolateMessages(parsed.getMessages(), variables, context)
                        : parsed.getMessages(),
            parsed.getEvaluationMetricKey(),
            factory);
      case COMPLETION:
      default:
        return new AICompletionConfig(
            key,
            parsed.isEnabled(),
            parsed.getModel(),
            parsed.getProvider(),
            interpolate ? interpolateMessages(parsed.getMessages(), variables, context)
                        : parsed.getMessages(),
            parsed.getJudgeConfiguration(),
            parsed.getTools(),
            factory,
            Evaluator.noop());
    }
  }

  /**
   * Builds the typed config straight from the caller-supplied default, used when the flag is absent
   * or cannot be evaluated. Prompt content is interpolated exactly as it is for an evaluated flag,
   * unless {@code interpolate} is {@code false} (template mode).
   */
  private AIConfig buildConfigFromDefault(
      String key,
      Mode mode,
      AIConfigDefault defaultValue,
      LDContext context,
      Map<String, Object> variables,
      boolean interpolate) {
    return buildConfigFromDefault(key, mode, defaultValue, context, variables, interpolate, null);
  }

  private AIConfig buildConfigFromDefault(
      String key,
      Mode mode,
      AIConfigDefault defaultValue,
      LDContext context,
      Map<String, Object> variables,
      boolean interpolate,
      String graphKey) {
    // Default configs still get real trackers — the configKey was requested even if no flag was found.
    // variationKey is null because no flag evaluation occurred.
    Supplier<LDAIConfigTracker> factory = trackerFactory(key, null, null, null, null, null, null, context, graphKey);
    switch (mode) {
      case AGENT: {
        AIAgentConfigDefault agent = (AIAgentConfigDefault) defaultValue;
        return new AIAgentConfig(
            key,
            agent.isEnabled(),
            agent.getModel(),
            agent.getProvider(),
            interpolate ? interpolate(agent.getInstructions(), variables, context)
                        : agent.getInstructions(),
            agent.getJudgeConfiguration(),
            agent.getTools(),
            factory,
            Evaluator.noop());
      }
      case JUDGE: {
        AIJudgeConfigDefault judge = (AIJudgeConfigDefault) defaultValue;
        return new AIJudgeConfig(
            key,
            judge.isEnabled(),
            judge.getModel(),
            judge.getProvider(),
            interpolate ? interpolateMessages(judge.getMessages(), variables, context)
                        : judge.getMessages(),
            judge.getEvaluationMetricKey(),
            factory);
      }
      case COMPLETION:
      default: {
        AICompletionConfigDefault completion = (AICompletionConfigDefault) defaultValue;
        return new AICompletionConfig(
            key,
            completion.isEnabled(),
            completion.getModel(),
            completion.getProvider(),
            interpolate ? interpolateMessages(completion.getMessages(), variables, context)
                        : completion.getMessages(),
            completion.getJudgeConfiguration(),
            completion.getTools(),
            factory,
            Evaluator.noop());
      }
    }
  }

  /**
   * Creates a per-evaluation tracker factory. Each call to the returned {@link Supplier} produces
   * a fresh {@link LDAIConfigTrackerImpl} with a new {@code runId}.
   */
  private Supplier<LDAIConfigTracker> trackerFactory(
      String configKey,
      String variationKey,
      Integer version,
      com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Model model,
      com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Provider provider,
      String modelKey,
      Integer modelVersion,
      LDContext context,
      String graphKey) {
    String modelName = model != null && model.getName() != null ? model.getName() : "";
    String providerName = provider != null && provider.getName() != null ? provider.getName() : "";
    int ver = version != null ? version : 1;
    int mVer = modelVersion != null ? modelVersion : 1;
    return () -> new LDAIConfigTrackerImpl(
        client,
        UUID.randomUUID().toString(),
        configKey,
        variationKey,
        ver,
        modelName,
        providerName,
        modelKey,
        mVer,
        context,
        graphKey,
        logger);
  }

  @Override
  public AgentGraphDefinition agentGraph(
      String graphKey, LDContext context, Map<String, Object> variables) {
    Objects.requireNonNull(graphKey, "graphKey");
    if (graphKey.trim().isEmpty()) {
      throw new IllegalArgumentException("graphKey must not be blank");
    }
    client.trackMetric(TRACK_USAGE_AGENT_GRAPH, context, LDValue.of(graphKey), 1);

    LDValue flagValue = client.jsonValueVariation(graphKey, context, LDValue.ofNull());
    AgentGraphFlagValue parsed = AgentGraphFlagValue.parse(
        (flagValue != null && flagValue.getType() == LDValueType.OBJECT) ? flagValue : null);

    Map<String, Object> effectiveVars = variables != null ? variables : Collections.emptyMap();
    Supplier<AIGraphTracker> trackerFactory = () -> new AIGraphTracker(
        client,
        UUID.randomUUID().toString(),
        graphKey,
        parsed.getVariationKey(),
        parsed.getVersion(),
        context,
        logger);

    AgentGraphDefinition disabled = new AgentGraphDefinition(
        parsed, Collections.<String, AgentGraphNode>emptyMap(), false, trackerFactory);

    // Validation step 1: _ldMeta.enabled
    if (!parsed.isEnabled()) {
      return disabled;
    }

    // Validation step 2: root must be non-empty
    String root = parsed.getRoot();
    if (root == null || root.isEmpty()) {
      return disabled;
    }

    // Validation step 3: all keys reachable from root (no unconnected nodes)
    Set<String> allKeys = AgentGraphDefinition.collectAllKeys(parsed);
    Set<String> reachableKeys = collectReachableKeys(parsed);
    for (String key : allKeys) {
      if (!reachableKeys.contains(key)) {
        return disabled;
      }
    }

    // Validation step 4: fetch each child config (without emitting usage events)
    Map<String, AIAgentConfig> configs = new HashMap<>();
    for (String key : allKeys) {
      AIAgentConfig config = evaluateAgent(key, context, null, effectiveVars, graphKey);
      if (!config.isEnabled()) {
        return disabled;
      }
      configs.put(key, config);
    }

    Map<String, AgentGraphNode> nodes = AgentGraphDefinition.buildNodes(parsed, configs);
    return new AgentGraphDefinition(parsed, nodes, true, trackerFactory);
  }

  private static Set<String> collectReachableKeys(AgentGraphFlagValue flagValue) {
    Set<String> visited = new HashSet<>();
    Queue<String> queue = new LinkedList<>();
    String root = flagValue.getRoot();
    if (root == null || root.isEmpty()) {
      return visited;
    }
    visited.add(root);
    queue.add(root);
    while (!queue.isEmpty()) {
      String key = queue.poll();
      List<GraphEdge> edges = flagValue.getEdges().get(key);
      if (edges != null) {
        for (GraphEdge edge : edges) {
          if (visited.add(edge.getKey())) {
            queue.add(edge.getKey());
          }
        }
      }
    }
    return visited;
  }

  @Override
  public AIGraphTracker createGraphTracker(String resumptionToken, LDContext context) {
    return AIGraphTracker.fromResumptionToken(resumptionToken, client, context, logger);
  }

  @Override
  public LDAIConfigTracker createTracker(String resumptionToken, LDContext context) {
    return LDAIConfigTrackerImpl.fromResumptionToken(resumptionToken, client, context, logger);
  }

  private List<Message> interpolateMessages(
      List<Message> messages, Map<String, Object> variables, LDContext context) {
    if (messages == null) {
      return null;
    }
    List<Message> result = new ArrayList<>(messages.size());
    for (Message message : messages) {
      result.add(message.withContent(interpolator.interpolate(message.getContent(), variables, context)));
    }
    return result;
  }

  private String interpolate(String template, Map<String, Object> variables, LDContext context) {
    return interpolator.interpolate(template, variables, context);
  }

}
