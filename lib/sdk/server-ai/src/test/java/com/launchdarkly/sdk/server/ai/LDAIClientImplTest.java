package com.launchdarkly.sdk.server.ai;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchdarkly.logging.LDLogLevel;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogCapture;
import com.launchdarkly.logging.Logs;
import com.launchdarkly.sdk.ArrayBuilder;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.ObjectBuilder;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Mode;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Message;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Model;
import com.launchdarkly.sdk.server.ai.internal.AISdkInfo;
import com.launchdarkly.sdk.server.interfaces.LDClientInterface;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.Before;
import org.junit.Test;

@SuppressWarnings("javadoc")
public class LDAIClientImplTest {
  private LDClientInterface client;
  private LogCapture logCapture;
  private LDLogger logger;
  private LDAIClientImpl ai;
  private final LDContext context = LDContext.create("user-key");

  @Before
  public void setUp() {
    client = mock(LDClientInterface.class);
    logCapture = Logs.capture();
    logger = LDLogger.withAdapter(logCapture, "test");
    ai = new LDAIClientImpl(client, logger);
  }

  private List<String> warnings() {
    return logCapture.getMessages().stream()
        .filter(m -> m.getLevel() == LDLogLevel.WARN)
        .map(LogCapture.Message::getText)
        .collect(Collectors.toList());
  }

  // ---- SDK info -------------------------------------------------------------

  @Test
  public void constructorEmitsSdkInfoEvent() {
    LDValue expected = LDValue.buildObject()
        .put("aiSdkName", AISdkInfo.NAME)
        .put("aiSdkVersion", AISdkInfo.VERSION)
        .put("aiSdkLanguage", AISdkInfo.LANGUAGE)
        .build();
    verify(client).trackMetric(eq("$ld:ai:sdk:info"), any(LDContext.class), eq(expected), eq(1.0));
  }

  // ---- Usage events ---------------------------------------------------------

  @Test
  public void completionConfigFiresUsageEvent() {
    when(client.jsonValueVariation(anyString(), any(), any())).thenReturn(LDValue.ofNull());
    ai.completionConfig("my-key", context, null, null);
    verify(client).trackMetric(eq("$ld:ai:usage:completion-config"), eq(context), eq(LDValue.of("my-key")), eq(1.0));
  }

  @Test
  public void agentConfigFiresUsageEvent() {
    when(client.jsonValueVariation(anyString(), any(), any())).thenReturn(LDValue.ofNull());
    ai.agentConfig("agent-key", context, null, null);
    verify(client).trackMetric(eq("$ld:ai:usage:agent-config"), eq(context), eq(LDValue.of("agent-key")), eq(1.0));
  }

  @Test
  public void judgeConfigFiresUsageEvent() {
    when(client.jsonValueVariation(anyString(), any(), any())).thenReturn(LDValue.ofNull());
    ai.judgeConfig("judge-key", context, null, null);
    verify(client).trackMetric(eq("$ld:ai:usage:judge-config"), eq(context), eq(LDValue.of("judge-key")), eq(1.0));
  }

  @Test
  public void agentConfigsFiresUsageEventWithCount() {
    when(client.jsonValueVariation(anyString(), any(), any())).thenReturn(LDValue.ofNull());
    List<AIAgentConfigRequest> requests = Arrays.asList(
        AIAgentConfigRequest.builder("a").build(),
        AIAgentConfigRequest.builder("b").build());
    ai.agentConfigs(requests, context);
    verify(client).trackMetric(eq("$ld:ai:usage:agent-configs"), eq(context), eq(LDValue.of(2)), eq(2.0));
  }

  // ---- Typed retrieval + interpolation -------------------------------------

  @Test
  public void completionConfigReturnsTypedConfigFromVariation() {
    String json = "{\"_ldMeta\":{\"enabled\":true,\"mode\":\"completion\"},"
        + "\"model\":{\"name\":\"gpt-4\"},"
        + "\"messages\":[{\"role\":\"system\",\"content\":\"Hello {{name}}\"}]}";
    when(client.jsonValueVariation(anyString(), any(), any())).thenReturn(LDValue.parse(json));

    Map<String, Object> variables = new HashMap<>();
    variables.put("name", "World");
    AICompletionConfig config = ai.completionConfig("key", context, null, variables);

    assertThat(config, is(notNullValue()));
    assertThat(config.getKey(), is("key"));
    assertThat(config.isEnabled(), is(true));
    assertThat(config.getMode(), is(Mode.COMPLETION));
    assertThat(config.getModel().getName(), is("gpt-4"));
    assertThat(config.getMessages(), hasSize(1));
    assertThat(config.getMessages().get(0).getContent(), is("Hello World"));
    assertThat(config.getMessages().get(0).getRole(), is(Message.Role.SYSTEM));
  }

  @Test
  public void completionConfigPropagatesModelMetadataToTracker() {
    String json = "{\"_ldMeta\":{\"enabled\":true,\"mode\":\"completion\","
        + "\"modelKey\":\"custom-gpt\",\"modelVersion\":7},"
        + "\"model\":{\"name\":\"gpt-4\"}}";
    when(client.jsonValueVariation(anyString(), any(), any())).thenReturn(LDValue.parse(json));

    AICompletionConfig config = ai.completionConfig("key", context, null, null);

    assertThat(config.getModel().getModelKey(), is("custom-gpt"));
    assertThat(config.getModel().getModelVersion(), is(7));
    assertThat(config.createTracker().getTrackData().getModelKey(), is("custom-gpt"));
    assertThat(config.createTracker().getTrackData().getModelVersion(), is(7));
  }

  @Test
  public void completionConfigDefaultsMissingModelMetadata() {
    String json = "{\"_ldMeta\":{\"enabled\":true,\"mode\":\"completion\"},"
        + "\"model\":{\"name\":\"gpt-4\"}}";
    when(client.jsonValueVariation(anyString(), any(), any())).thenReturn(LDValue.parse(json));

    AICompletionConfig config = ai.completionConfig("key", context, null, null);

    assertThat(config.getModel().getModelKey(), is(nullValue()));
    assertThat(config.getModel().getModelVersion(), is(1));
    assertThat(config.createTracker().getTrackData().getModelKey(), is(nullValue()));
    assertThat(config.createTracker().getTrackData().getModelVersion(), is(1));
  }

  @Test
  public void interpolationExposesContextAsLdctx() {
    String json = "{\"_ldMeta\":{\"enabled\":true,\"mode\":\"completion\"},"
        + "\"messages\":[{\"role\":\"user\",\"content\":\"{{ldctx.key}}\"}]}";
    when(client.jsonValueVariation(anyString(), any(), any())).thenReturn(LDValue.parse(json));

    AICompletionConfig config = ai.completionConfig("key", LDContext.create("ctx-123"), null, null);
    assertThat(config.getMessages().get(0).getContent(), is("ctx-123"));
  }

  @Test
  public void agentConfigInterpolatesInstructions() {
    String json = "{\"_ldMeta\":{\"enabled\":true,\"mode\":\"agent\"},"
        + "\"instructions\":\"You research {{topic}}\"}";
    when(client.jsonValueVariation(anyString(), any(), any())).thenReturn(LDValue.parse(json));

    Map<String, Object> variables = new HashMap<>();
    variables.put("topic", "climate");
    AIAgentConfig config = ai.agentConfig("key", context, null, variables);

    assertThat(config.getMode(), is(Mode.AGENT));
    assertThat(config.getInstructions(), is("You research climate"));
  }

  @Test
  public void judgeConfigResolvesFirstNonBlankEvaluationMetricKey() {
    String json = "{\"_ldMeta\":{\"enabled\":true,\"mode\":\"judge\"},"
        + "\"evaluationMetricKeys\":[\"   \",\"\",\"relevance\",\"other\"]}";
    when(client.jsonValueVariation(anyString(), any(), any())).thenReturn(LDValue.parse(json));

    AIJudgeConfig config = ai.judgeConfig("key", context, null, null);
    assertThat(config.getMode(), is(Mode.JUDGE));
    assertThat(config.getEvaluationMetricKey(), is("relevance"));
  }

  // ---- Mode validation ------------------------------------------------------

  @Test
  public void modeMismatchReturnsDefaultConfigAndWarnsOnce() {
    String agentJson = "{\"_ldMeta\":{\"enabled\":true,\"mode\":\"agent\"},"
        + "\"instructions\":\"hi\"}";
    when(client.jsonValueVariation(anyString(), any(), any())).thenReturn(LDValue.parse(agentJson));

    // Requesting a completion config against an agent-mode flag returns the caller's default.
    AICompletionConfigDefault dflt = AICompletionConfigDefault.builder()
        .enabled(true)
        .model(Model.builder("default-model").build())
        .messages(Arrays.asList(new Message(Message.Role.SYSTEM, "default {{x}}")))
        .build();
    Map<String, Object> variables = new HashMap<>();
    variables.put("x", "value");

    AICompletionConfig config = ai.completionConfig("key", context, dflt, variables);

    assertThat(config, is(notNullValue()));
    assertThat(config.getMode(), is(Mode.COMPLETION));
    assertThat(config.isEnabled(), is(true));
    assertThat(config.getModel().getName(), is("default-model"));
    assertThat(config.getMessages().get(0).getContent(), is("default value"));
    assertThat(warnings(), hasSize(1));
    assertThat(warnings().get(0), containsString("mode mismatch"));
  }

  @Test
  public void matchingModeDoesNotWarn() {
    String json = "{\"_ldMeta\":{\"enabled\":true,\"mode\":\"completion\"}}";
    when(client.jsonValueVariation(anyString(), any(), any())).thenReturn(LDValue.parse(json));
    ai.completionConfig("key", context, null, null);
    assertThat(warnings(), is(empty()));
  }

  // ---- Default semantics ----------------------------------------------------

  @Test
  public void absentFlagReturnsConfiguredDefault() {
    // Simulate an absent flag: the base SDK returns the null sentinel default we pass in, which the
    // client treats as "flag not found" and resolves to the caller's typed default.
    when(client.jsonValueVariation(anyString(), any(), any()))
        .thenAnswer(inv -> inv.getArgument(2, LDValue.class));

    AICompletionConfigDefault dflt = AICompletionConfigDefault.builder()
        .enabled(true)
        .model(Model.builder("default-model").build())
        .messages(Arrays.asList(new Message(Message.Role.SYSTEM, "default {{x}}")))
        .build();

    Map<String, Object> variables = new HashMap<>();
    variables.put("x", "value");
    AICompletionConfig config = ai.completionConfig("key", context, dflt, variables);

    assertThat(config.isEnabled(), is(true));
    assertThat(config.getModel().getName(), is("default-model"));
    assertThat(config.getMessages().get(0).getContent(), is("default value"));
  }

  @Test
  public void nullDefaultYieldsDisabledConfigWhenAbsent() {
    when(client.jsonValueVariation(anyString(), any(), any()))
        .thenAnswer(inv -> inv.getArgument(2, LDValue.class));
    AICompletionConfig config = ai.completionConfig("key", context, null, null);
    assertThat(config.isEnabled(), is(false));
  }

  @Test
  public void doesNotMergeMissingFieldsFromDefault() {
    // The flag is present and enabled but omits messages; the default supplies messages.
    // Per the JS-aligned semantics, the result reflects the variation as-is (no per-field merge),
    // so messages remain absent.
    String json = "{\"_ldMeta\":{\"enabled\":true,\"mode\":\"completion\"},\"model\":{\"name\":\"flag-model\"}}";
    when(client.jsonValueVariation(anyString(), any(), any())).thenReturn(LDValue.parse(json));

    AICompletionConfigDefault dflt = AICompletionConfigDefault.builder()
        .enabled(false)
        .messages(Arrays.asList(new Message(Message.Role.SYSTEM, "should not appear")))
        .build();
    AICompletionConfig config = ai.completionConfig("key", context, dflt, variables(/* none */));

    assertThat(config.getModel().getName(), is("flag-model"));
    assertThat(config.getMessages(), is(nullValue()));
  }

  // ---- agentConfigs ---------------------------------------------------------

  @Test
  public void agentConfigsReturnsMapKeyedByRequestPreservingOrder() {
    when(client.jsonValueVariation(anyString(), any(), any())).thenAnswer(inv -> {
      String key = inv.getArgument(0);
      return LDValue.parse("{\"_ldMeta\":{\"enabled\":true,\"mode\":\"agent\"},"
          + "\"instructions\":\"I am " + key + "\"}");
    });

    List<AIAgentConfigRequest> requests = Arrays.asList(
        AIAgentConfigRequest.builder("research").build(),
        AIAgentConfigRequest.builder("writing").build());
    Map<String, AIAgentConfig> agents = ai.agentConfigs(requests, context);

    assertThat(new ArrayList<>(agents.keySet()), contains("research", "writing"));
    assertThat(agents.get("research").getInstructions(), is("I am research"));
    assertThat(agents.get("writing").getInstructions(), is("I am writing"));
  }

  @Test
  public void agentConfigsHandlesEmptyList() {
    Map<String, AIAgentConfig> agents = ai.agentConfigs(new ArrayList<>(), context);
    assertThat(agents.entrySet(), is(empty()));
    verify(client).trackMetric(eq("$ld:ai:usage:agent-configs"), eq(context), eq(LDValue.of(0)), eq(0.0));
  }

  @Test
  public void agentConfigsUsageCountExcludesNullEntries() {
    when(client.jsonValueVariation(anyString(), any(), any())).thenReturn(LDValue.ofNull());
    List<AIAgentConfigRequest> requests = Arrays.asList(
        AIAgentConfigRequest.builder("a").build(),
        null,
        AIAgentConfigRequest.builder("b").build());
    Map<String, AIAgentConfig> agents = ai.agentConfigs(requests, context);

    assertThat(agents.keySet(), containsInAnyOrder("a", "b"));
    verify(client).trackMetric(eq("$ld:ai:usage:agent-configs"), eq(context), eq(LDValue.of(2)), eq(2.0));
  }

  // ---- Template config methods ----------------------------------------------

  // Tracking events

  @Test
  public void completionConfigTemplateFiresTemplateUsageEvent() {
    when(client.jsonValueVariation(anyString(), any(), any())).thenReturn(LDValue.ofNull());
    ai.completionConfigTemplate("my-key", context, null);
    verify(client).trackMetric(
        eq("$ld:ai:usage:completion-config-template"), eq(context), eq(LDValue.of("my-key")), eq(1.0));
  }

  @Test
  public void agentConfigTemplateFiresTemplateUsageEvent() {
    when(client.jsonValueVariation(anyString(), any(), any())).thenReturn(LDValue.ofNull());
    ai.agentConfigTemplate("agent-key", context, null);
    verify(client).trackMetric(
        eq("$ld:ai:usage:agent-config-template"), eq(context), eq(LDValue.of("agent-key")), eq(1.0));
  }

  @Test
  public void judgeConfigTemplateFiresTemplateUsageEvent() {
    when(client.jsonValueVariation(anyString(), any(), any())).thenReturn(LDValue.ofNull());
    ai.judgeConfigTemplate("judge-key", context, null);
    verify(client).trackMetric(
        eq("$ld:ai:usage:judge-config-template"), eq(context), eq(LDValue.of("judge-key")), eq(1.0));
  }

  // Placeholder preservation

  @Test
  public void completionConfigTemplatePreservesPlaceholders() {
    String json = "{\"_ldMeta\":{\"enabled\":true,\"mode\":\"completion\"},"
        + "\"messages\":[{\"role\":\"system\",\"content\":\"Hello {{name}}\"}]}";
    when(client.jsonValueVariation(anyString(), any(), any())).thenReturn(LDValue.parse(json));

    AICompletionConfig config = ai.completionConfigTemplate("key", context, null);
    assertThat(config.getMessages().get(0).getContent(), is("Hello {{name}}"));
  }

  @Test
  public void agentConfigTemplatePreservesPlaceholders() {
    String json = "{\"_ldMeta\":{\"enabled\":true,\"mode\":\"agent\"},"
        + "\"instructions\":\"You research {{topic}}\"}";
    when(client.jsonValueVariation(anyString(), any(), any())).thenReturn(LDValue.parse(json));

    AIAgentConfig config = ai.agentConfigTemplate("key", context, null);
    assertThat(config.getInstructions(), is("You research {{topic}}"));
  }

  @Test
  public void judgeConfigTemplatePreservesPlaceholders() {
    String json = "{\"_ldMeta\":{\"enabled\":true,\"mode\":\"judge\"},"
        + "\"messages\":[{\"role\":\"user\",\"content\":\"Rate {{response}}\"}]}";
    when(client.jsonValueVariation(anyString(), any(), any())).thenReturn(LDValue.parse(json));

    AIJudgeConfig config = ai.judgeConfigTemplate("key", context, null);
    assertThat(config.getMessages().get(0).getContent(), is("Rate {{response}}"));
  }

  // ldctx non-interpolation

  @Test
  public void completionConfigTemplateDoesNotInterpolateLdctx() {
    String json = "{\"_ldMeta\":{\"enabled\":true,\"mode\":\"completion\"},"
        + "\"messages\":[{\"role\":\"user\",\"content\":\"{{ldctx.key}}\"}]}";
    when(client.jsonValueVariation(anyString(), any(), any())).thenReturn(LDValue.parse(json));

    AICompletionConfig config = ai.completionConfigTemplate("key", LDContext.create("ctx-123"), null);
    assertThat(config.getMessages().get(0).getContent(), is("{{ldctx.key}}"));
  }

  @Test
  public void agentConfigTemplateDoesNotInterpolateLdctx() {
    String json = "{\"_ldMeta\":{\"enabled\":true,\"mode\":\"agent\"},"
        + "\"instructions\":\"Hello {{ldctx.key}}\"}";
    when(client.jsonValueVariation(anyString(), any(), any())).thenReturn(LDValue.parse(json));

    AIAgentConfig config = ai.agentConfigTemplate("key", LDContext.create("ctx-123"), null);
    assertThat(config.getInstructions(), is("Hello {{ldctx.key}}"));
  }

  @Test
  public void judgeConfigTemplateDoesNotInterpolateLdctx() {
    String json = "{\"_ldMeta\":{\"enabled\":true,\"mode\":\"judge\"},"
        + "\"messages\":[{\"role\":\"user\",\"content\":\"{{ldctx.key}}\"}]}";
    when(client.jsonValueVariation(anyString(), any(), any())).thenReturn(LDValue.parse(json));

    AIJudgeConfig config = ai.judgeConfigTemplate("key", LDContext.create("ctx-123"), null);
    assertThat(config.getMessages().get(0).getContent(), is("{{ldctx.key}}"));
  }

  // Null default yields disabled config

  @Test
  public void completionConfigTemplateNullDefaultYieldsDisabled() {
    when(client.jsonValueVariation(anyString(), any(), any()))
        .thenAnswer(inv -> inv.getArgument(2, LDValue.class));
    AICompletionConfig config = ai.completionConfigTemplate("key", context, null);
    assertThat(config.isEnabled(), is(false));
  }

  @Test
  public void agentConfigTemplateNullDefaultYieldsDisabled() {
    when(client.jsonValueVariation(anyString(), any(), any()))
        .thenAnswer(inv -> inv.getArgument(2, LDValue.class));
    AIAgentConfig config = ai.agentConfigTemplate("key", context, null);
    assertThat(config.isEnabled(), is(false));
  }

  @Test
  public void judgeConfigTemplateNullDefaultYieldsDisabled() {
    when(client.jsonValueVariation(anyString(), any(), any()))
        .thenAnswer(inv -> inv.getArgument(2, LDValue.class));
    AIJudgeConfig config = ai.judgeConfigTemplate("key", context, null);
    assertThat(config.isEnabled(), is(false));
  }

  // Tracker non-null

  @Test
  public void completionConfigTemplateHasTracker() {
    String json = "{\"_ldMeta\":{\"enabled\":true,\"mode\":\"completion\"},"
        + "\"messages\":[{\"role\":\"system\",\"content\":\"Hello {{name}}\"}]}";
    when(client.jsonValueVariation(anyString(), any(), any())).thenReturn(LDValue.parse(json));

    AICompletionConfig config = ai.completionConfigTemplate("key", context, null);
    assertThat(config.createTracker(), is(notNullValue()));
  }

  @Test
  public void agentConfigTemplateHasTracker() {
    String json = "{\"_ldMeta\":{\"enabled\":true,\"mode\":\"agent\"},"
        + "\"instructions\":\"You research {{topic}}\"}";
    when(client.jsonValueVariation(anyString(), any(), any())).thenReturn(LDValue.parse(json));

    AIAgentConfig config = ai.agentConfigTemplate("key", context, null);
    assertThat(config.createTracker(), is(notNullValue()));
  }

  @Test
  public void judgeConfigTemplateHasTracker() {
    String json = "{\"_ldMeta\":{\"enabled\":true,\"mode\":\"judge\"},"
        + "\"messages\":[{\"role\":\"user\",\"content\":\"Rate {{response}}\"}]}";
    when(client.jsonValueVariation(anyString(), any(), any())).thenReturn(LDValue.parse(json));

    AIJudgeConfig config = ai.judgeConfigTemplate("key", context, null);
    assertThat(config.createTracker(), is(notNullValue()));
  }

  private static Map<String, Object> variables() {
    return new HashMap<>();
  }

  // ---- agentGraph -----------------------------------------------------------

  private static LDValue graphFlagValue(String root, boolean enabled, String variationKey,
      String... edges) {
    ObjectBuilder edgesObj = LDValue.buildObject();
    // edges are pairs: [source, target, source, target, ...]
    Map<String, ArrayBuilder> edgeMap = new LinkedHashMap<>();
    for (int i = 0; i + 1 < edges.length; i += 2) {
      String src = edges[i], tgt = edges[i + 1];
      if (!edgeMap.containsKey(src)) {
        edgeMap.put(src, LDValue.buildArray());
      }
      edgeMap.get(src).add(LDValue.buildObject().put("key", tgt).build());
    }
    for (Map.Entry<String, ArrayBuilder> e : edgeMap.entrySet()) {
      edgesObj.put(e.getKey(), e.getValue().build());
    }
    ObjectBuilder meta = LDValue.buildObject()
        .put("enabled", enabled)
        .put("version", 1);
    if (variationKey != null) {
      meta.put("variationKey", variationKey);
    }
    return LDValue.buildObject()
        .put("root", root)
        .put("edges", edgesObj.build())
        .put("_ldMeta", meta.build())
        .build();
  }

  private static LDValue agentFlagValue(boolean enabled) {
    return LDValue.parse("{\"_ldMeta\":{\"enabled\":" + enabled + ",\"mode\":\"agent\"},"
        + "\"instructions\":\"test instructions\"}");
  }

  @Test(expected = NullPointerException.class)
  public void agentGraphThrowsOnNullGraphKey() {
    ai.agentGraph(null, context, null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void agentGraphThrowsOnBlankGraphKey() {
    ai.agentGraph("  ", context, null);
  }

  @Test
  public void agentGraphFiresUsageEvent() {
    when(client.jsonValueVariation(eq("g"), any(), any()))
        .thenReturn(graphFlagValue("node-a", true, "v1", "node-a", "node-b"));
    when(client.jsonValueVariation(eq("node-a"), any(), any())).thenReturn(agentFlagValue(true));
    when(client.jsonValueVariation(eq("node-b"), any(), any())).thenReturn(agentFlagValue(true));

    ai.agentGraph("g", context, null);

    verify(client).trackMetric(
        eq("$ld:ai:usage:agent-graph"), eq(context), eq(LDValue.of("g")), eq(1.0));
  }

  @Test
  public void agentGraphDoesNotFireNodeLevelUsageEvents() {
    when(client.jsonValueVariation(eq("g"), any(), any()))
        .thenReturn(graphFlagValue("node-a", true, null));
    when(client.jsonValueVariation(eq("node-a"), any(), any())).thenReturn(agentFlagValue(true));

    ai.agentGraph("g", context, null);

    verify(client, never()).trackMetric(eq("$ld:ai:usage:agent-config"), any(), any(), anyDouble());
  }

  @Test
  public void agentGraphReturnsEnabledGraphForValidFlag() {
    when(client.jsonValueVariation(eq("g"), any(), any()))
        .thenReturn(graphFlagValue("node-a", true, "v1", "node-a", "node-b"));
    when(client.jsonValueVariation(eq("node-a"), any(), any())).thenReturn(agentFlagValue(true));
    when(client.jsonValueVariation(eq("node-b"), any(), any())).thenReturn(agentFlagValue(true));

    AgentGraphDefinition graph = ai.agentGraph("g", context, null);

    assertThat(graph.isEnabled(), is(true));
    assertThat(graph.rootNode(), is(notNullValue()));
    assertThat(graph.rootNode().getKey(), is("node-a"));
    assertThat(graph.getNode("node-b"), is(notNullValue()));
  }

  @Test
  public void agentGraphReturnsDisabledWhenFlagDisabled() {
    when(client.jsonValueVariation(eq("g"), any(), any()))
        .thenReturn(graphFlagValue("node-a", false, null));

    AgentGraphDefinition graph = ai.agentGraph("g", context, null);

    assertThat(graph.isEnabled(), is(false));
  }

  @Test
  public void agentGraphReturnsDisabledWhenRootMissing() {
    when(client.jsonValueVariation(eq("g"), any(), any()))
        .thenReturn(graphFlagValue("", true, null));

    AgentGraphDefinition graph = ai.agentGraph("g", context, null);

    assertThat(graph.isEnabled(), is(false));
  }

  @Test
  public void agentGraphReturnsDisabledWhenUnreachableNode() {
    // node-a -> node-b, but flag also declares node-c which is unreachable from root
    LDValue flag = LDValue.buildObject()
        .put("root", "node-a")
        .put("edges", LDValue.buildObject()
            .put("node-a", LDValue.buildArray()
                .add(LDValue.buildObject().put("key", "node-b").build())
                .build())
            .put("node-c", LDValue.buildArray()  // unreachable source
                .add(LDValue.buildObject().put("key", "node-d").build())
                .build())
            .build())
        .put("_ldMeta", LDValue.buildObject().put("enabled", true).build())
        .build();
    when(client.jsonValueVariation(eq("g"), any(), any())).thenReturn(flag);

    AgentGraphDefinition graph = ai.agentGraph("g", context, null);

    assertThat(graph.isEnabled(), is(false));
  }

  @Test
  public void agentGraphReturnsDisabledWhenAnyChildConfigDisabled() {
    when(client.jsonValueVariation(eq("g"), any(), any()))
        .thenReturn(graphFlagValue("node-a", true, null, "node-a", "node-b"));
    when(client.jsonValueVariation(eq("node-a"), any(), any())).thenReturn(agentFlagValue(true));
    when(client.jsonValueVariation(eq("node-b"), any(), any())).thenReturn(agentFlagValue(false));

    AgentGraphDefinition graph = ai.agentGraph("g", context, null);

    assertThat(graph.isEnabled(), is(false));
  }

  @Test
  public void agentGraphReturnsDisabledForNonObjectFlagValue() {
    when(client.jsonValueVariation(eq("g"), any(), any())).thenReturn(LDValue.ofNull());

    AgentGraphDefinition graph = ai.agentGraph("g", context, null);

    assertThat(graph.isEnabled(), is(false));
  }

  @Test
  public void agentGraphNoVariablesOverloadCallsThreeArgVersion() {
    when(client.jsonValueVariation(eq("g"), any(), any()))
        .thenReturn(graphFlagValue("node-a", true, null));
    when(client.jsonValueVariation(eq("node-a"), any(), any())).thenReturn(agentFlagValue(true));

    AgentGraphDefinition graph = ai.agentGraph("g", context);
    assertThat(graph.isEnabled(), is(true));
  }

  @Test
  public void agentGraphChildConfigsIncludeGraphKey() {
    when(client.jsonValueVariation(eq("g"), any(), any()))
        .thenReturn(graphFlagValue("node-a", true, "var-1"));
    when(client.jsonValueVariation(eq("node-a"), any(), any())).thenReturn(agentFlagValue(true));

    AgentGraphDefinition graph = ai.agentGraph("g", context, null);
    assertThat(graph.isEnabled(), is(true));

    // Verify: when a node tracker is created, graphKey is present in its track data
    LDAIConfigTracker nodeTracker = graph.getNode("node-a").getConfig().createTracker();
    assertThat(nodeTracker.getTrackData().getGraphKey(), is("g"));
  }

  // ---- createGraphTracker --------------------------------------------------

  @Test
  public void createGraphTrackerRoundTripsToken() {
    when(client.jsonValueVariation(eq("g"), any(), any()))
        .thenReturn(graphFlagValue("node-a", true, "var-1"));
    when(client.jsonValueVariation(eq("node-a"), any(), any())).thenReturn(agentFlagValue(true));

    AgentGraphDefinition graph = ai.agentGraph("g", context, null);
    AIGraphTracker original = graph.createTracker();
    assertThat(original, is(notNullValue()));

    String token = original.getResumptionToken();
    AIGraphTracker reconstructed = ai.createGraphTracker(token, context);
    assertThat(reconstructed.getResumptionToken(), is(token));
  }

  @Test(expected = IllegalArgumentException.class)
  public void createGraphTrackerThrowsForMalformedToken() {
    ai.createGraphTracker("not-valid-base64!!!", context);
  }

  private static <K, V> java.util.LinkedHashMap<K, V> newLinkedHashMap() {
    return new java.util.LinkedHashMap<>();
  }
}
