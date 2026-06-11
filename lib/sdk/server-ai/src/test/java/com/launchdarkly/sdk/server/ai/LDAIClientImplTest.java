package com.launchdarkly.sdk.server.ai;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchdarkly.logging.LDLogLevel;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogCapture;
import com.launchdarkly.logging.Logs;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Mode;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Message;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Model;
import com.launchdarkly.sdk.server.interfaces.LDClientInterface;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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
        .put("aiSdkName", "launchdarkly-java-server-sdk-ai")
        .put("aiSdkVersion", "0.1.0")
        .put("aiSdkLanguage", "java")
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

  private static Map<String, Object> variables() {
    return new HashMap<>();
  }
}
