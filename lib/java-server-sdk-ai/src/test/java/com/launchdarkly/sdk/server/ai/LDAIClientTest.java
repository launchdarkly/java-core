package com.launchdarkly.sdk.server.ai;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.ai.datamodel.AIAgentConfig;
import com.launchdarkly.sdk.server.ai.datamodel.AIAgentConfigDefault;
import com.launchdarkly.sdk.server.ai.datamodel.AIAgentConfigRequest;
import com.launchdarkly.sdk.server.ai.datamodel.AICompletionConfig;
import com.launchdarkly.sdk.server.ai.datamodel.AICompletionConfigDefault;
import com.launchdarkly.sdk.server.ai.datamodel.AIJudgeConfig;
import com.launchdarkly.sdk.server.ai.datamodel.AIJudgeConfigDefault;
import com.launchdarkly.sdk.server.ai.datamodel.LDMessage;
import com.launchdarkly.sdk.server.ai.datamodel.ModelConfig;
import com.launchdarkly.sdk.server.ai.datamodel.ProviderConfig;
import com.launchdarkly.sdk.server.ai.internal.AISdkInfo;
import com.launchdarkly.sdk.server.ai.tracking.AIConfigTracker;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LDAIClientTest {
  private static final LDContext USER = LDContext.create("user-key");

  private MockLDClient newClient() {
    return new MockLDClient();
  }

  @Test
  public void sdkInfoIsTrackedOnConstruction() {
    MockLDClient client = newClient();
    new LDAIClient(client);

    List<MockLDClient.TrackEvent> events = client.eventsNamed("$ld:ai:sdk:info");
    assertEquals(1, events.size());
    MockLDClient.TrackEvent event = events.get(0);
    assertEquals("ld-internal-tracking", event.context.getKey());
    assertEquals("ld_ai", event.context.getKind().toString());
    assertTrue(event.context.isAnonymous());
    assertEquals(AISdkInfo.AI_SDK_NAME, event.data.get("aiSdkName").stringValue());
    assertEquals(AISdkInfo.AI_SDK_VERSION, event.data.get("aiSdkVersion").stringValue());
    assertEquals("java", event.data.get("aiSdkLanguage").stringValue());
    assertEquals(1.0, event.metricValue, 0.0);
  }

  @Test
  public void completionConfigTracksUsageEvent() {
    MockLDClient client = newClient();
    LDAIClient ai = new LDAIClient(client);

    ai.completionConfig("my-config", USER, AICompletionConfigDefault.disabled(), null);

    List<MockLDClient.TrackEvent> events = client.eventsNamed("$ld:ai:usage:completion-config");
    assertEquals(1, events.size());
    assertEquals("my-config", events.get(0).data.stringValue());
    assertEquals(1.0, events.get(0).metricValue, 0.0);
  }

  @Test
  public void completionConfigInterpolatesMessagesAndExposesModel() {
    MockLDClient client = newClient().setFlag("model-config", LDValue.parse(
        "{\"_ldMeta\":{\"enabled\":true,\"variationKey\":\"abcd\",\"version\":1},"
            + "\"model\":{\"name\":\"fakeModel\",\"parameters\":{\"temperature\":0.5,\"maxTokens\":4096},"
            + "\"custom\":{\"extra-attribute\":\"value\"}},"
            + "\"provider\":{\"name\":\"fakeProvider\"},"
            + "\"messages\":[{\"role\":\"system\",\"content\":\"Hello, {{name}}!\"}]}"));
    LDAIClient ai = new LDAIClient(client);

    AICompletionConfig config = ai.completionConfig(
        "model-config", USER, AICompletionConfigDefault.disabled(), Collections.singletonMap("name", "World"));

    assertTrue(config.isEnabled());
    assertEquals("Hello, World!", config.getMessages().get(0).getContent());
    assertEquals("system", config.getMessages().get(0).getRole());
    assertEquals("fakeModel", config.getModel().getName());
    assertEquals(0.5, config.getModel().getParameter("temperature").doubleValue(), 0.0);
    assertEquals(4096, config.getModel().getParameter("maxTokens").intValue());
    assertEquals("value", config.getModel().getCustom("extra-attribute").stringValue());
    assertEquals("fakeProvider", config.getProvider().getName());
    assertNotNull(config.getEvaluator());
  }

  @Test
  public void completionConfigUsesDefaultWhenFlagMissing() {
    MockLDClient client = newClient();
    LDAIClient ai = new LDAIClient(client);

    AICompletionConfigDefault def = AICompletionConfigDefault.builder()
        .enabled(true)
        .model(new ModelConfig("fallback-model"))
        .messages(Collections.singletonList(LDMessage.system("Hello, {{name}}!")))
        .build();

    AICompletionConfig config = ai.completionConfig("missing-flag", USER, def,
        Collections.singletonMap("name", "World"));

    assertTrue(config.isEnabled());
    assertEquals("Hello, World!", config.getMessages().get(0).getContent());
    assertEquals("fallback-model", config.getModel().getName());
  }

  @Test
  public void completionConfigWithoutDefaultIsDisabled() {
    LDAIClient ai = new LDAIClient(newClient());
    AICompletionConfig config = ai.completionConfig("missing-flag", USER, null, null);
    assertFalse(config.isEnabled());
  }

  @Test
  public void completionConfigFallsBackToDefaultOnNonObjectVariation() {
    MockLDClient client = newClient().setFlag("bad-config", LDValue.of("not an object"));
    LDAIClient ai = new LDAIClient(client);

    AICompletionConfigDefault def = AICompletionConfigDefault.builder()
        .enabled(true)
        .messages(Collections.singletonList(LDMessage.system("Hi {{name}}")))
        .build();

    AICompletionConfig config = ai.completionConfig("bad-config", USER, def,
        Collections.singletonMap("name", "Bailey"));

    assertTrue(config.isEnabled());
    assertEquals("Hi Bailey", config.getMessages().get(0).getContent());
  }

  @Test
  public void disabledFlagYieldsDisabledConfig() {
    MockLDClient client = newClient().setFlag("off-config", LDValue.parse(
        "{\"_ldMeta\":{\"enabled\":false,\"version\":1},\"model\":{\"name\":\"m\"},\"messages\":[]}"));
    LDAIClient ai = new LDAIClient(client);

    AICompletionConfig config = ai.completionConfig("off-config", USER,
        AICompletionConfigDefault.disabled(), null);

    assertFalse(config.isEnabled());
    assertEquals("m", config.getModel().getName());
  }

  @Test
  public void interpolatesSingleContextAttributes() {
    MockLDClient client = newClient().setFlag("ctx", LDValue.parse(
        "{\"_ldMeta\":{\"enabled\":true,\"version\":1},\"model\":{\"name\":\"m\"},"
            + "\"messages\":[{\"role\":\"system\",\"content\":"
            + "\"Hello, {{ldctx.name}}! Is your last name {{ldctx.last}}?\"}]}"));
    LDAIClient ai = new LDAIClient(client);

    LDContext context = LDContext.builder("user-key").name("Sandy").set("last", "Beaches").build();
    AICompletionConfig config = ai.completionConfig("ctx", context, AICompletionConfigDefault.disabled(), null);

    assertEquals("Hello, Sandy! Is your last name Beaches?", config.getMessages().get(0).getContent());
  }

  @Test
  public void interpolatesMultiContextAttributes() {
    MockLDClient client = newClient().setFlag("multi-ctx", LDValue.parse(
        "{\"_ldMeta\":{\"enabled\":true,\"version\":1},\"model\":{\"name\":\"m\"},"
            + "\"messages\":[{\"role\":\"system\",\"content\":"
            + "\"Hello, {{ldctx.user.name}}! Do you work for {{ldctx.org.shortname}}?\"}]}"));
    LDAIClient ai = new LDAIClient(client);

    LDContext user = LDContext.builder("user-key").name("Sandy").build();
    LDContext org = LDContext.builder("org-key").kind("org").name("LaunchDarkly").set("shortname", "LD").build();
    LDContext multi = LDContext.createMulti(user, org);

    AICompletionConfig config = ai.completionConfig("multi-ctx", multi, AICompletionConfigDefault.disabled(), null);
    assertEquals("Hello, Sandy! Do you work for LD?", config.getMessages().get(0).getContent());
  }

  @Test
  public void resolvesRootLevelTools() {
    MockLDClient client = newClient().setFlag("tools-config", LDValue.parse(
        "{\"_ldMeta\":{\"enabled\":true,\"version\":1},\"model\":{\"name\":\"m\"},\"messages\":[],"
            + "\"tools\":{\"web-search-tool\":{\"name\":\"web-search-tool\",\"type\":\"function\","
            + "\"parameters\":{\"type\":\"object\"},\"customParameters\":{\"x\":\"y\"}}}}"));
    LDAIClient ai = new LDAIClient(client);

    AICompletionConfig config = ai.completionConfig("tools-config", USER,
        AICompletionConfigDefault.disabled(), null);

    assertEquals("function", config.getTools().get("web-search-tool").getType());
    assertEquals("y", config.getTools().get("web-search-tool").getCustomParameters().get("x").stringValue());
  }

  @Test
  public void resolvesToolsFromModelParametersWhenNoRootTools() {
    MockLDClient client = newClient().setFlag("param-tools", LDValue.parse(
        "{\"_ldMeta\":{\"enabled\":true,\"version\":1},\"messages\":[],"
            + "\"model\":{\"name\":\"m\",\"parameters\":{\"tools\":[{\"name\":\"t1\",\"type\":\"function\"}]}}}"));
    LDAIClient ai = new LDAIClient(client);

    AICompletionConfig config = ai.completionConfig("param-tools", USER,
        AICompletionConfigDefault.disabled(), null);

    assertEquals("function", config.getTools().get("t1").getType());
  }

  @Test
  public void parsesJudgeConfiguration() {
    MockLDClient client = newClient().setFlag("judged-config", LDValue.parse(
        "{\"_ldMeta\":{\"enabled\":true,\"version\":1},\"model\":{\"name\":\"m\"},\"messages\":[],"
            + "\"judgeConfiguration\":{\"judges\":[{\"key\":\"relevance-judge\",\"samplingRate\":0.1}]}}"));
    LDAIClient ai = new LDAIClient(client);

    AICompletionConfig config = ai.completionConfig("judged-config", USER,
        AICompletionConfigDefault.disabled(), null);

    assertEquals(1, config.getJudgeConfiguration().getJudges().size());
    assertEquals("relevance-judge", config.getJudgeConfiguration().getJudges().get(0).getKey());
    assertEquals(0.1, config.getJudgeConfiguration().getJudges().get(0).getSamplingRate(), 0.0);
  }

  @Test
  public void createTrackerProducesFreshRunIdEachCall() {
    MockLDClient client = newClient().setFlag("model-config", LDValue.parse(
        "{\"_ldMeta\":{\"enabled\":true,\"variationKey\":\"v1\",\"version\":1},\"model\":{\"name\":\"m\"},\"messages\":[]}"));
    LDAIClient ai = new LDAIClient(client);

    AICompletionConfig config = ai.completionConfig("model-config", USER,
        AICompletionConfigDefault.disabled(), null);
    AIConfigTracker t1 = config.createTracker();
    AIConfigTracker t2 = config.createTracker();

    t1.trackSuccess();
    t2.trackSuccess();
    List<MockLDClient.TrackEvent> successes = client.eventsNamed("$ld:ai:generation:success");
    assertEquals(2, successes.size());
    assertFalse(successes.get(0).data.get("runId").stringValue()
        .equals(successes.get(1).data.get("runId").stringValue()));
  }

  @Test
  public void createTrackerFromTokenPreservesRun() {
    MockLDClient client = newClient().setFlag("model-config", LDValue.parse(
        "{\"_ldMeta\":{\"enabled\":true,\"variationKey\":\"v1\",\"version\":3},\"model\":{\"name\":\"gpt-4\"},\"messages\":[]}"));
    LDAIClient ai = new LDAIClient(client);

    AIConfigTracker original = ai.completionConfig("model-config", USER,
        AICompletionConfigDefault.disabled(), null).createTracker();
    String token = original.getResumptionToken();

    AIConfigTracker restored = ai.createTracker(token, USER);
    restored.trackSuccess();

    MockLDClient.TrackEvent event = client.eventsNamed("$ld:ai:generation:success").get(0);
    assertEquals("model-config", event.data.get("configKey").stringValue());
    assertEquals(3, event.data.get("version").intValue());
    assertEquals("v1", event.data.get("variationKey").stringValue());
  }

  @Test
  public void agentConfigInterpolatesInstructionsAndTracksUsage() {
    MockLDClient client = newClient().setFlag("research_agent", LDValue.parse(
        "{\"_ldMeta\":{\"enabled\":true,\"variationKey\":\"abcd\",\"version\":1},"
            + "\"model\":{\"name\":\"agent-model\"},\"provider\":{\"name\":\"openai\"},"
            + "\"instructions\":\"You are a research assistant specializing in {{topic}}.\"}"));
    LDAIClient ai = new LDAIClient(client);

    AIAgentConfig config = ai.agentConfig("research_agent", USER, AIAgentConfigDefault.disabled(),
        Collections.singletonMap("topic", "climate change"));

    assertTrue(config.isEnabled());
    assertEquals("You are a research assistant specializing in climate change.", config.getInstructions());
    assertEquals("agent-model", config.getModel().getName());
    assertEquals("openai", config.getProvider().getName());
    assertEquals(1, client.eventsNamed("$ld:ai:usage:agent-config").size());
  }

  @Test
  public void agentConfigFallsBackToDefaultModelAndProvider() {
    MockLDClient client = newClient().setFlag("agent-no-model", LDValue.parse(
        "{\"_ldMeta\":{\"enabled\":true,\"version\":1},\"instructions\":\"hello\"}"));
    LDAIClient ai = new LDAIClient(client);

    AIAgentConfigDefault def = AIAgentConfigDefault.builder()
        .enabled(true)
        .model(new ModelConfig("default-model"))
        .provider(new ProviderConfig("default-provider"))
        .build();

    AIAgentConfig config = ai.agentConfig("agent-no-model", USER, def, null);

    assertEquals("default-model", config.getModel().getName());
    assertEquals("default-provider", config.getProvider().getName());
    assertEquals("hello", config.getInstructions());
  }

  @Test
  public void agentConfigsReturnsMapAndTracksCount() {
    MockLDClient client = newClient()
        .setFlag("research_agent", LDValue.parse(
            "{\"_ldMeta\":{\"enabled\":true,\"version\":1},\"instructions\":\"Research {{topic}}.\"}"))
        .setFlag("writing_agent", LDValue.parse(
            "{\"_ldMeta\":{\"enabled\":true,\"version\":1},\"instructions\":\"Write in {{style}} style.\"}"));
    LDAIClient ai = new LDAIClient(client);

    Map<String, Object> researchVars = new HashMap<>();
    researchVars.put("topic", "climate change");
    Map<String, Object> writingVars = new HashMap<>();
    writingVars.put("style", "academic");

    List<AIAgentConfigRequest> requests = Arrays.asList(
        new AIAgentConfigRequest("research_agent", AIAgentConfigDefault.disabled(), researchVars),
        new AIAgentConfigRequest("writing_agent", AIAgentConfigDefault.disabled(), writingVars));

    Map<String, AIAgentConfig> agents = ai.agentConfigs(requests, USER);

    assertEquals("Research climate change.", agents.get("research_agent").getInstructions());
    assertEquals("Write in academic style.", agents.get("writing_agent").getInstructions());

    MockLDClient.TrackEvent event = client.eventsNamed("$ld:ai:usage:agent-configs").get(0);
    assertEquals(2, event.data.intValue());
    assertEquals(2.0, event.metricValue, 0.0);
  }

  @Test
  public void judgeConfigExtractsMetricKeyAndTracksUsage() {
    MockLDClient client = newClient().setFlag("relevance-judge", LDValue.parse(
        "{\"_ldMeta\":{\"enabled\":true,\"version\":1},\"model\":{\"name\":\"gpt-4\"},"
            + "\"provider\":{\"name\":\"openai\"},"
            + "\"messages\":[{\"role\":\"system\",\"content\":\"Evaluate for {{metric}}.\"}],"
            + "\"evaluationMetricKey\":\"$ld:ai:judge:relevance\"}"));
    LDAIClient ai = new LDAIClient(client);

    AIJudgeConfig config = ai.judgeConfig("relevance-judge", USER, AIJudgeConfigDefault.disabled(),
        Collections.singletonMap("metric", "relevance"));

    assertTrue(config.isEnabled());
    assertEquals("$ld:ai:judge:relevance", config.getEvaluationMetricKey());
    assertEquals("Evaluate for relevance.", config.getMessages().get(0).getContent());
    assertEquals(1, client.eventsNamed("$ld:ai:usage:judge-config").size());
  }

  @Test
  public void variationVersionDefaultsToOneWhenMissing() {
    MockLDClient client = newClient().setFlag("no-version", LDValue.parse(
        "{\"_ldMeta\":{\"enabled\":true},\"model\":{\"name\":\"m\"},\"messages\":[]}"));
    LDAIClient ai = new LDAIClient(client);

    AIConfigTracker tracker = ai.completionConfig("no-version", USER,
        AICompletionConfigDefault.disabled(), null).createTracker();
    tracker.trackSuccess();

    assertEquals(1, client.eventsNamed("$ld:ai:generation:success").get(0).data.get("version").intValue());
  }

  @Test
  public void emptyMessagesListProducesEmptyMessages() {
    MockLDClient client = newClient().setFlag("empty-messages", LDValue.parse(
        "{\"_ldMeta\":{\"enabled\":true,\"version\":1},\"model\":{\"name\":\"m\"},\"messages\":[]}"));
    LDAIClient ai = new LDAIClient(client);

    AICompletionConfig config = ai.completionConfig("empty-messages", USER,
        AICompletionConfigDefault.disabled(), null);
    assertNotNull(config.getMessages());
    assertTrue(config.getMessages().isEmpty());
  }

  @Test
  public void missingMessagesYieldsNull() {
    MockLDClient client = newClient().setFlag("no-messages", LDValue.parse(
        "{\"_ldMeta\":{\"enabled\":true,\"version\":1},\"model\":{\"name\":\"m\"}}"));
    LDAIClient ai = new LDAIClient(client);

    AICompletionConfig config = ai.completionConfig("no-messages", USER,
        AICompletionConfigDefault.disabled(), null);
    assertNull(config.getMessages());
  }
}
