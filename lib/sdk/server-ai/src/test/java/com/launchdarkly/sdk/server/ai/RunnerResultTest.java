package com.launchdarkly.sdk.server.ai;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.notNullValue;

import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.AIMetrics;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

@SuppressWarnings("javadoc")
public class RunnerResultTest {
  private static final AIMetrics METRICS = AIMetrics.builder().success(true).build();

  @Test
  public void builderSetsContent() {
    RunnerResult result = RunnerResult.builder("hello", METRICS).build();
    assertThat(result.getContent(), is("hello"));
  }

  @Test
  public void builderSetsMetrics() {
    RunnerResult result = RunnerResult.builder(null, METRICS).build();
    assertThat(result.getMetrics(), is(METRICS));
  }

  @Test
  public void rawIsNullByDefault() {
    RunnerResult result = RunnerResult.builder("content", METRICS).build();
    assertThat(result.getRaw(), nullValue());
  }

  @Test
  public void parsedIsNullByDefault() {
    RunnerResult result = RunnerResult.builder("content", METRICS).build();
    assertThat(result.getParsed(), nullValue());
  }

  @Test
  public void builderSetsRaw() {
    Object raw = new Object();
    RunnerResult result = RunnerResult.builder("content", METRICS).raw(raw).build();
    assertThat(result.getRaw(), is(raw));
  }

  @Test
  public void builderSetsParsed() {
    Map<String, Object> parsed = new HashMap<>();
    parsed.put("score", 0.8);
    RunnerResult result = RunnerResult.builder("content", METRICS).parsed(parsed).build();
    assertThat(result.getParsed(), notNullValue());
    assertThat(result.getParsed().get("score"), is(0.8));
  }

  @Test
  public void parsedMapIsImmutable() {
    Map<String, Object> parsed = new HashMap<>();
    parsed.put("key", "value");
    RunnerResult result = RunnerResult.builder("content", METRICS).parsed(parsed).build();
    try {
      result.getParsed().put("extra", "should fail");
      assertThat("Expected UnsupportedOperationException", false);
    } catch (UnsupportedOperationException ignored) {
    }
  }

  @Test
  public void mutatingOriginalMapDoesNotAffectResult() {
    Map<String, Object> parsed = new HashMap<>();
    parsed.put("key", "original");
    RunnerResult result = RunnerResult.builder("content", METRICS).parsed(parsed).build();
    parsed.put("key", "mutated");
    assertThat(result.getParsed().get("key"), is("original"));
  }

  @Test
  public void contentCanBeNull() {
    RunnerResult result = RunnerResult.builder(null, METRICS).build();
    assertThat(result.getContent(), nullValue());
  }
}
