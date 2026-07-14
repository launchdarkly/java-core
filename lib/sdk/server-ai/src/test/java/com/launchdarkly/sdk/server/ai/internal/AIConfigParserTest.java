package com.launchdarkly.sdk.server.ai.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.JudgeConfiguration;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Message;
import com.launchdarkly.sdk.server.ai.datamodel.LDAIConfigTypes.Mode;

import java.util.List;

import org.junit.Test;

@SuppressWarnings("javadoc")
public class AIConfigParserTest {
  @Test
  public void parsesFullCompletionConfig() {
    LDValue value = LDValue.parse("{"
        + "\"_ldMeta\":{\"variationKey\":\"v1\",\"enabled\":true,\"version\":3,\"mode\":\"completion\"},"
        + "\"model\":{\"name\":\"gpt-4\",\"modelKey\":\"custom-gpt\",\"modelVersion\":7,"
        + "\"parameters\":{\"temperature\":0.7,\"maxTokens\":100},"
        + "\"custom\":{\"team\":\"core\"}},"
        + "\"provider\":{\"name\":\"openai\"},"
        + "\"messages\":[{\"role\":\"system\",\"content\":\"You are {{persona}}.\"},"
        + "{\"role\":\"user\",\"content\":\"Hi\"}],"
        + "\"judgeConfiguration\":{\"judges\":[{\"key\":\"j1\",\"samplingRate\":0.5}]},"
        + "\"tools\":{\"search\":{\"name\":\"search\",\"description\":\"d\",\"type\":\"function\","
        + "\"parameters\":{\"q\":\"string\"}}}"
        + "}");

    AIConfigFlagValue parsed = AIConfigParser.parse(value);

    assertThat(parsed.isEnabled(), is(true));
    assertThat(parsed.getVariationKey(), is("v1"));
    assertThat(parsed.getVersion(), is(3));
    assertThat(parsed.getMode(), is(Mode.COMPLETION));
    assertThat(parsed.getModel().getName(), is("gpt-4"));
    assertThat(parsed.getModel().getModelKey(), is("custom-gpt"));
    assertThat(parsed.getModel().getModelVersion(), is(7));
    assertThat(parsed.getModel().getParameter("temperature"), is((Object) 0.7));
    assertThat(parsed.getModel().getParameter("maxTokens"), is((Object) 100L));
    assertThat(parsed.getModel().getCustom("team"), is((Object) "core"));
    assertThat(parsed.getProvider().getName(), is("openai"));
    assertThat(parsed.getMessages(), hasSize(2));
    assertThat(parsed.getMessages().get(0).getRole(), is(Message.Role.SYSTEM));
    JudgeConfiguration judges = parsed.getJudgeConfiguration();
    assertThat(judges.getJudges(), hasSize(1));
    assertThat(judges.getJudges().get(0).getKey(), is("j1"));
    assertThat(judges.getJudges().get(0).getSamplingRate(), is(0.5));
    assertThat(parsed.getTools().keySet(), contains("search"));
    assertThat(parsed.getTools().get("search").getType(), is("function"));
  }

  @Test
  public void modelMetadataDefaultsWhenMissingOrWrongType() {
    AIConfigFlagValue missing = AIConfigParser.parse(
        LDValue.parse("{\"model\":{\"name\":\"gpt-4\"}}"));
    assertThat(missing.getModel().getModelKey(), is(nullValue()));
    assertThat(missing.getModel().getModelVersion(), is(1));

    AIConfigFlagValue wrongType = AIConfigParser.parse(
        LDValue.parse("{\"model\":{\"modelKey\":3,\"modelVersion\":\"two\"}}"));
    assertThat(wrongType.getModel().getModelKey(), is(nullValue()));
    assertThat(wrongType.getModel().getModelVersion(), is(1));
  }

  @Test
  public void parsesAgentInstructions() {
    LDValue value = LDValue.parse("{\"_ldMeta\":{\"enabled\":true,\"mode\":\"agent\"},"
        + "\"instructions\":\"Help {{name}}\"}");
    AIConfigFlagValue parsed = AIConfigParser.parse(value);
    assertThat(parsed.getMode(), is(Mode.AGENT));
    assertThat(parsed.getInstructions(), is("Help {{name}}"));
    assertThat(parsed.getMessages(), is(nullValue()));
  }

  @Test
  public void nullValueYieldsAllAbsentDisabled() {
    AIConfigFlagValue parsed = AIConfigParser.parse(LDValue.ofNull());
    assertThat(parsed.isEnabled(), is(false));
    assertThat(parsed.getEnabled(), is(nullValue()));
    assertThat(parsed.getMode(), is(nullValue()));
    assertThat(parsed.getModel(), is(nullValue()));
    assertThat(parsed.getMessages(), is(nullValue()));
  }

  @Test
  public void nonObjectValueIsSafe() {
    assertThat(AIConfigParser.parse(LDValue.of("a string")).isEnabled(), is(false));
    assertThat(AIConfigParser.parse(LDValue.parse("[1,2]")).isEnabled(), is(false));
  }

  @Test
  public void skipsMalformedMessagesButKeepsValidOnes() {
    LDValue value = LDValue.parse("{\"messages\":["
        + "{\"role\":\"system\"},"               // missing content
        + "{\"content\":\"orphan\"},"             // missing role
        + "{\"role\":\"bogus\",\"content\":\"x\"}," // unknown role
        + "{\"role\":\"user\",\"content\":123},"  // non-string content
        + "{\"role\":\"assistant\",\"content\":\"valid\"}"
        + "]}");
    List<Message> messages = AIConfigParser.parse(value).getMessages();
    assertThat(messages, hasSize(1));
    assertThat(messages.get(0).getRole(), is(Message.Role.ASSISTANT));
    assertThat(messages.get(0).getContent(), is("valid"));
  }

  @Test
  public void messagesFieldOfWrongTypeYieldsNull() {
    AIConfigFlagValue parsed = AIConfigParser.parse(LDValue.parse("{\"messages\":{\"not\":\"array\"}}"));
    assertThat(parsed.getMessages(), is(nullValue()));
  }

  @Test
  public void unknownModeResolvesToNull() {
    AIConfigFlagValue parsed = AIConfigParser.parse(LDValue.parse("{\"_ldMeta\":{\"mode\":\"weird\"}}"));
    assertThat(parsed.getMode(), is(nullValue()));
  }

  @Test
  public void resolvesToolsFromModelParametersFallback() {
    LDValue value = LDValue.parse("{\"model\":{\"name\":\"m\",\"parameters\":{\"tools\":["
        + "{\"name\":\"t1\",\"type\":\"function\"},"
        + "{\"type\":\"function\"}"  // no name -> skipped
        + "]}}}");
    AIConfigFlagValue parsed = AIConfigParser.parse(value);
    assertThat(parsed.getTools().keySet(), contains("t1"));
  }

  @Test
  public void rootLevelToolsTakePrecedenceOverModelParameters() {
    LDValue value = LDValue.parse("{"
        + "\"tools\":{\"root\":{\"name\":\"root\"}},"
        + "\"model\":{\"parameters\":{\"tools\":[{\"name\":\"fromParams\"}]}}"
        + "}");
    AIConfigFlagValue parsed = AIConfigParser.parse(value);
    assertThat(parsed.getTools().keySet(), contains("root"));
  }

  @Test
  public void evaluationMetricKeyPrefersScalarTrimmed() {
    AIConfigFlagValue parsed = AIConfigParser.parse(
        LDValue.parse("{\"evaluationMetricKey\":\"  primary  \",\"evaluationMetricKeys\":[\"other\"]}"));
    assertThat(parsed.getEvaluationMetricKey(), is("primary"));
  }

  @Test
  public void evaluationMetricKeyFallsBackToFirstNonBlankInList() {
    AIConfigFlagValue parsed = AIConfigParser.parse(
        LDValue.parse("{\"evaluationMetricKey\":\"   \",\"evaluationMetricKeys\":[\"\",\"  \",\"good\",\"later\"]}"));
    assertThat(parsed.getEvaluationMetricKey(), is("good"));
  }

  @Test
  public void evaluationMetricKeyAbsentYieldsNull() {
    assertThat(AIConfigParser.parse(LDValue.parse("{}")).getEvaluationMetricKey(), is(nullValue()));
  }

  @Test
  public void judgeEntriesMissingKeyAreSkippedAndSamplingRateDefaultsToZero() {
    LDValue value = LDValue.parse("{\"judgeConfiguration\":{\"judges\":["
        + "{\"samplingRate\":0.9},"            // missing key -> skipped
        + "{\"key\":\"j-no-rate\"}"            // missing samplingRate -> 0.0
        + "]}}");
    JudgeConfiguration judges = AIConfigParser.parse(value).getJudgeConfiguration();
    assertThat(judges.getJudges(), hasSize(1));
    assertThat(judges.getJudges().get(0).getKey(), is("j-no-rate"));
    assertThat(judges.getJudges().get(0).getSamplingRate(), is(0.0));
  }

  @Test
  public void enabledFalseIsDistinctFromAbsent() {
    AIConfigFlagValue explicitFalse =
        AIConfigParser.parse(LDValue.parse("{\"_ldMeta\":{\"enabled\":false}}"));
    assertThat(explicitFalse.getEnabled(), is(false));
    assertThat(explicitFalse.isEnabled(), is(false));

    AIConfigFlagValue absent = AIConfigParser.parse(LDValue.parse("{\"_ldMeta\":{}}"));
    assertThat(absent.getEnabled(), is(nullValue()));
    assertThat(absent.isEnabled(), is(false));
  }

  @Test
  public void judgeConfigurationWithNoJudgesIsEmptyNotNull() {
    AIConfigFlagValue parsed =
        AIConfigParser.parse(LDValue.parse("{\"judgeConfiguration\":{}}"));
    assertThat(parsed.getJudgeConfiguration().getJudges(), is(empty()));
  }
}
