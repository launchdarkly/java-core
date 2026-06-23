package com.launchdarkly.sdk.server.ai.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import org.junit.Test;

@SuppressWarnings("javadoc")
public class ResumptionTokensTest {

  // ---- encode + decode round-trips ------------------------------------------

  @Test
  public void roundTripWithAllFields() {
    String token = ResumptionTokens.encode("run-1", "config-key", "var-abc", 2, "graph-x");
    ResumptionTokens.Decoded d = ResumptionTokens.decode(token);
    assertThat(d.getRunId(), is("run-1"));
    assertThat(d.getConfigKey(), is("config-key"));
    assertThat(d.getVariationKey(), is("var-abc"));
    assertThat(d.getVersion(), is(2));
    assertThat(d.getGraphKey(), is("graph-x"));
  }

  @Test
  public void roundTripWithNullVariationKey() {
    String token = ResumptionTokens.encode("run-1", "config-key", null, 1, null);
    ResumptionTokens.Decoded d = ResumptionTokens.decode(token);
    assertThat(d.getRunId(), is("run-1"));
    assertThat(d.getConfigKey(), is("config-key"));
    assertThat(d.getVariationKey(), is(nullValue()));
    assertThat(d.getVersion(), is(1));
    assertThat(d.getGraphKey(), is(nullValue()));
  }

  @Test
  public void roundTripWithNullGraphKey() {
    String token = ResumptionTokens.encode("run-2", "cfg", "v1", 3, null);
    ResumptionTokens.Decoded d = ResumptionTokens.decode(token);
    assertThat(d.getGraphKey(), is(nullValue()));
    assertThat(d.getVariationKey(), is("v1"));
  }

  @Test
  public void variationKeyOmittedFromTokenWhenNull() {
    // Tokens with null variationKey should NOT contain the "variationKey" JSON field.
    String token = ResumptionTokens.encode("r", "c", null, 1, null);
    // Decode and check no variationKey
    ResumptionTokens.Decoded d = ResumptionTokens.decode(token);
    assertThat(d.getVariationKey(), is(nullValue()));
  }

  @Test
  public void graphKeyOmittedFromTokenWhenNull() {
    String token = ResumptionTokens.encode("r", "c", "v", 1, null);
    ResumptionTokens.Decoded d = ResumptionTokens.decode(token);
    assertThat(d.getGraphKey(), is(nullValue()));
  }

  // ---- special character escaping -------------------------------------------

  @Test
  public void roundTripWithSpecialCharactersInKeys() {
    String runId = "run\"with\\special\nchars";
    String configKey = "config\twith\rtabs";
    String token = ResumptionTokens.encode(runId, configKey, null, 1, null);
    ResumptionTokens.Decoded d = ResumptionTokens.decode(token);
    assertThat(d.getRunId(), is(runId));
    assertThat(d.getConfigKey(), is(configKey));
  }

  @Test
  public void roundTripWithUnicodeInKeys() {
    String runId = "run-\u00e9\u4e2d\u6587";
    String token = ResumptionTokens.encode(runId, "cfg", null, 1, null);
    ResumptionTokens.Decoded d = ResumptionTokens.decode(token);
    assertThat(d.getRunId(), is(runId));
  }

  // ---- version round-trip ---------------------------------------------------

  @Test
  public void versionIsPreservedOnRoundTrip() {
    String token = ResumptionTokens.encode("r", "c", null, 1, null);
    assertThat(ResumptionTokens.decode(token).getVersion(), is(1));
  }

  // ---- decode error handling ------------------------------------------------

  @Test(expected = IllegalArgumentException.class)
  public void decodeRejectsNull() {
    ResumptionTokens.decode(null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void decodeRejectsOversizedToken() {
    // Build a token larger than 4096 bytes
    String largeValue = new String(new char[5000]).replace('\0', 'x');
    String json = "{\"runId\":\"" + largeValue + "\",\"configKey\":\"c\",\"version\":1}";
    String token = java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    ResumptionTokens.decode(token);
  }

  @Test(expected = IllegalArgumentException.class)
  public void decodeRejectsInvalidBase64() {
    ResumptionTokens.decode("not-valid-base64!!!!");
  }

  @Test(expected = IllegalArgumentException.class)
  public void decodeRejectsMissingRunId() {
    String json = "{\"configKey\":\"c\",\"version\":1}";
    String token = java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    ResumptionTokens.decode(token);
  }

  @Test(expected = IllegalArgumentException.class)
  public void decodeRejectsMissingConfigKey() {
    String json = "{\"runId\":\"r\",\"version\":1}";
    String token = java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    ResumptionTokens.decode(token);
  }

  @Test(expected = IllegalArgumentException.class)
  public void decodeRejectsMissingVersion() {
    String json = "{\"runId\":\"r\",\"configKey\":\"c\"}";
    String token = java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    ResumptionTokens.decode(token);
  }

  @Test(expected = IllegalArgumentException.class)
  public void decodeRejectsNonIntegerVersion() {
    String json = "{\"runId\":\"r\",\"configKey\":\"c\",\"version\":\"one\"}";
    String token = java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    ResumptionTokens.decode(token);
  }

  @Test(expected = IllegalArgumentException.class)
  public void decodeRejectsNonObjectJson() {
    String json = "[\"not\",\"an\",\"object\"]";
    String token = java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    ResumptionTokens.decode(token);
  }

  // ---- escapeJson helper ----------------------------------------------------

  @Test
  public void escapeJsonHandlesControlCharacters() {
    assertThat(ResumptionTokens.escapeJson("\n\r\t"), is("\\n\\r\\t"));
    assertThat(ResumptionTokens.escapeJson("\"hello\""), is("\\\"hello\\\""));
    assertThat(ResumptionTokens.escapeJson("back\\slash"), is("back\\\\slash"));
  }

  @Test
  public void escapeJsonReturnsEmptyStringForNull() {
    assertThat(ResumptionTokens.escapeJson(null), is(""));
  }
}
