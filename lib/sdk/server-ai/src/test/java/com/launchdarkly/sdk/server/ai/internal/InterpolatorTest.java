package com.launchdarkly.sdk.server.ai.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import com.launchdarkly.sdk.LDContext;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

@SuppressWarnings("javadoc")
public class InterpolatorTest {
  private final Interpolator interpolator = new Interpolator();

  @Test
  public void rendersSimpleVariable() {
    Map<String, Object> vars = new HashMap<>();
    vars.put("name", "World");
    assertThat(interpolator.interpolate("Hello {{name}}", vars), is("Hello World"));
  }

  @Test
  public void doesNotHtmlEscape() {
    Map<String, Object> vars = new HashMap<>();
    vars.put("x", "<b>&\"'");
    // Matches the JS/Python policy: values are emitted verbatim, never HTML-escaped.
    assertThat(interpolator.interpolate("{{x}}", vars), is("<b>&\"'"));
  }

  @Test
  public void tripleStacheMatchesDoubleStache() {
    Map<String, Object> vars = new HashMap<>();
    vars.put("x", "<b>");
    assertThat(interpolator.interpolate("{{{x}}}", vars), is(interpolator.interpolate("{{x}}", vars)));
  }

  @Test
  public void missingVariableRendersEmpty() {
    assertThat(interpolator.interpolate("[{{missing}}]", new HashMap<String, Object>()), is("[]"));
  }

  @Test
  public void nullVariableRendersEmpty() {
    Map<String, Object> vars = new HashMap<>();
    vars.put("x", null);
    assertThat(interpolator.interpolate("[{{x}}]", vars), is("[]"));
  }

  @Test
  public void nullTemplateReturnsNull() {
    assertThat(interpolator.interpolate(null, new HashMap<String, Object>()), is(nullValue()));
    assertThat(interpolator.interpolate(null, new HashMap<String, Object>(), LDContext.create("k")),
        is(nullValue()));
  }

  @Test
  public void exposesContextAsLdctx() {
    LDContext context = LDContext.builder("user-key")
        .name("Bob")
        .set("tier", "gold")
        .build();
    String result = interpolator.interpolate(
        "{{ldctx.kind}}/{{ldctx.key}}/{{ldctx.name}}/{{ldctx.tier}}", null, context);
    assertThat(result, is("user/user-key/Bob/gold"));
  }

  @Test
  public void exposesNestedCustomAttribute() {
    LDContext context = LDContext.builder("user-key")
        .set("address", com.launchdarkly.sdk.LDValue.buildObject().put("city", "Oakland").build())
        .build();
    assertThat(interpolator.interpolate("{{ldctx.address.city}}", null, context), is("Oakland"));
  }

  @Test
  public void exposesMultiKindContextByKind() {
    LDContext multi = LDContext.createMulti(
        LDContext.builder("user-key").name("Bob").build(),
        LDContext.builder(com.launchdarkly.sdk.ContextKind.of("org"), "org-key").set("tier", "gold").build());
    String result = interpolator.interpolate(
        "{{ldctx.kind}}/{{ldctx.user.key}}/{{ldctx.user.name}}/{{ldctx.org.tier}}", null, multi);
    assertThat(result, is("multi/user-key/Bob/gold"));
  }

  @Test
  public void multiKindNestedContextsOmitKind() {
    LDContext multi = LDContext.createMulti(
        LDContext.builder("user-key").build(),
        LDContext.builder(com.launchdarkly.sdk.ContextKind.of("org"), "org-key").build());
    // Standard LaunchDarkly context JSON omits "kind" on the per-kind objects of a multi-kind
    // context, so {{ldctx.user.kind}} renders empty rather than echoing the kind name.
    assertThat(
        interpolator.interpolate("[{{ldctx.user.kind}}]", null, multi), is("[]"));
  }

  @Test
  public void ldctxOverridesUserSuppliedValue() {
    Map<String, Object> userLdctx = new HashMap<>();
    userLdctx.put("key", "WRONG");
    Map<String, Object> vars = new HashMap<>();
    vars.put("ldctx", userLdctx);

    LDContext context = LDContext.create("right-key");
    assertThat(interpolator.interpolate("{{ldctx.key}}", vars, context), is("right-key"));
  }

  @Test
  public void nullContextLeavesLdctxEmpty() {
    assertThat(interpolator.interpolate("[{{ldctx.key}}]", null, null), is("[]"));
  }

  @Test
  public void cachedTemplateRendersConsistentlyAcrossInvocations() {
    Map<String, Object> first = new HashMap<>();
    first.put("v", "one");
    Map<String, Object> second = new HashMap<>();
    second.put("v", "two");

    assertThat(interpolator.interpolate("value={{v}}", first), is("value=one"));
    // Second render uses the cached compiled template but the new variable map.
    assertThat(interpolator.interpolate("value={{v}}", second), is("value=two"));
  }
}
