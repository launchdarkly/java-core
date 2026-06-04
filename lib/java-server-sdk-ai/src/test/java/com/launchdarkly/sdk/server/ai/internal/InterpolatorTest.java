package com.launchdarkly.sdk.server.ai.internal;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class InterpolatorTest {
  @Test
  public void interpolatesSimpleVariable() {
    Map<String, Object> variables = Collections.singletonMap("name", "World");
    assertEquals("Hello, World!", Interpolator.interpolate("Hello, {{name}}!", variables));
  }

  @Test
  public void missingVariableRendersEmpty() {
    assertEquals("Hello, !", Interpolator.interpolate("Hello, {{name}}!", new HashMap<>()));
  }

  @Test
  public void nullVariableRendersEmpty() {
    Map<String, Object> variables = new HashMap<>();
    variables.put("name", null);
    assertEquals("Hello, !", Interpolator.interpolate("Hello, {{name}}!", variables));
  }

  @Test
  public void escapesOnlyChevronCharacters() {
    Map<String, Object> variables = Collections.singletonMap("x", "<b>&\"hi\"</b>");
    assertEquals("a &lt;b&gt;&amp;&quot;hi&quot;&lt;/b&gt; b",
        Interpolator.interpolate("a {{x}} b", variables));
  }

  @Test
  public void tripleBracesDoNotEscape() {
    Map<String, Object> variables = Collections.singletonMap("x", "<b>&\"hi\"</b>");
    assertEquals("a <b>&\"hi\"</b> b", Interpolator.interpolate("a {{{x}}} b", variables));
  }

  @Test
  public void slashIsNotEscaped() {
    Map<String, Object> variables = Collections.singletonMap("x", "mind/type");
    assertEquals("mind/type", Interpolator.interpolate("{{x}}", variables));
  }

  @Test
  public void interpolatesNestedContext() {
    Map<String, Object> ldctx = new HashMap<>();
    ldctx.put("name", "Sandy");
    Map<String, Object> variables = Collections.singletonMap("ldctx", ldctx);
    assertEquals("Hi Sandy", Interpolator.interpolate("Hi {{ldctx.name}}", variables));
  }
}
