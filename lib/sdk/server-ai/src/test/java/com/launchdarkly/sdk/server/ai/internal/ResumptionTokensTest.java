package com.launchdarkly.sdk.server.ai.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.Test;

@SuppressWarnings("javadoc")
public class ResumptionTokensTest {
  // Byte-for-byte fixtures produced by base64url-encoding the canonical JSON, matching the other
  // LaunchDarkly SDKs. If these change, cross-SDK resumption is broken.
  private static final String FIXTURE_WITH_VARIATION =
      "eyJydW5JZCI6InJ1bi0xIiwiY29uZmlnS2V5IjoiY2ZnIiwidmFyaWF0aW9uS2V5IjoidjEiLCJ2ZXJzaW9uIjozfQ";
  private static final String FIXTURE_EMPTY_VARIATION =
      "eyJydW5JZCI6ImFiYyIsImNvbmZpZ0tleSI6Im15LWNvbmZpZyIsInZhcmlhdGlvbktleSI6IiIsInZlcnNpb24iOjB9";

  @Test
  public void encodeIsByteCompatibleWithFixture() {
    assertThat(ResumptionTokens.encode("run-1", "cfg", "v1", 3, null), is(FIXTURE_WITH_VARIATION));
  }

  @Test
  public void encodeAlwaysEmitsVariationKeyEvenWhenEmpty() {
    assertThat(ResumptionTokens.encode("abc", "my-config", "", 0, null), is(FIXTURE_EMPTY_VARIATION));
  }

  @Test
  public void encodeTreatsNullVariationKeyAsEmpty() {
    assertThat(ResumptionTokens.encode("abc", "my-config", null, 0, null), is(FIXTURE_EMPTY_VARIATION));
  }

  @Test
  public void canonicalKeyOrderIsFixed() {
    String token = ResumptionTokens.encode("r", "c", "v", 7, "g");
    String json = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
    assertThat(json, is("{\"runId\":\"r\",\"configKey\":\"c\",\"variationKey\":\"v\",\"version\":7,\"graphKey\":\"g\"}"));
  }

  @Test
  public void graphKeyOmittedWhenNull() {
    String token = ResumptionTokens.encode("r", "c", "v", 7, null);
    String json = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
    assertThat(json, is("{\"runId\":\"r\",\"configKey\":\"c\",\"variationKey\":\"v\",\"version\":7}"));
  }

  @Test
  public void roundTripsAllFields() {
    String token = ResumptionTokens.encode("run-9", "cfg-9", "var-9", 42, "graph-9");
    ResumptionTokens.Decoded d = ResumptionTokens.decode(token);
    assertThat(d.getRunId(), is("run-9"));
    assertThat(d.getConfigKey(), is("cfg-9"));
    assertThat(d.getVariationKey(), is("var-9"));
    assertThat(d.getVersion(), is(42));
    assertThat(d.getGraphKey(), is("graph-9"));
  }

  @Test
  public void decodeFixtureWithVariation() {
    ResumptionTokens.Decoded d = ResumptionTokens.decode(FIXTURE_WITH_VARIATION);
    assertThat(d.getRunId(), is("run-1"));
    assertThat(d.getConfigKey(), is("cfg"));
    assertThat(d.getVariationKey(), is("v1"));
    assertThat(d.getVersion(), is(3));
    assertThat(d.getGraphKey(), is(nullValue()));
  }

  @Test
  public void decodeDefaultsAbsentVariationKeyToEmpty() {
    String token = base64Url("{\"runId\":\"r\",\"configKey\":\"c\",\"version\":1}");
    ResumptionTokens.Decoded d = ResumptionTokens.decode(token);
    assertThat(d.getVariationKey(), is(""));
  }

  @Test
  public void escapesSpecialCharactersInValues() {
    String token = ResumptionTokens.encode("a\"b\\c\nd", "cfg", "v", 1, null);
    ResumptionTokens.Decoded d = ResumptionTokens.decode(token);
    assertThat(d.getRunId(), is("a\"b\\c\nd"));
  }

  @Test
  public void rejectsNullToken() {
    assertThrows(IllegalArgumentException.class, () -> ResumptionTokens.decode(null));
  }

  @Test
  public void rejectsInvalidBase64() {
    assertThrows(IllegalArgumentException.class, () -> ResumptionTokens.decode("!!!not base64!!!"));
  }

  @Test
  public void rejectsNonObjectJson() {
    assertThrows(IllegalArgumentException.class, () -> ResumptionTokens.decode(base64Url("[1,2,3]")));
  }

  @Test
  public void rejectsMissingRunId() {
    assertThrows(IllegalArgumentException.class,
        () -> ResumptionTokens.decode(base64Url("{\"configKey\":\"c\",\"version\":1}")));
  }

  @Test
  public void rejectsMistypedVersion() {
    assertThrows(IllegalArgumentException.class,
        () -> ResumptionTokens.decode(base64Url("{\"runId\":\"r\",\"configKey\":\"c\",\"version\":\"x\"}")));
  }

  @Test
  public void rejectsNonIntegerVersion() {
    assertThrows(IllegalArgumentException.class,
        () -> ResumptionTokens.decode(base64Url("{\"runId\":\"r\",\"configKey\":\"c\",\"version\":1.5}")));
  }

  @Test
  public void rejectsOversizedPayload() {
    StringBuilder big = new StringBuilder("{\"runId\":\"");
    for (int i = 0; i < ResumptionTokens.MAX_PAYLOAD_BYTES + 100; i++) {
      big.append('a');
    }
    big.append("\",\"configKey\":\"c\",\"version\":1}");
    String token = base64Url(big.toString());
    assertThat(token, is(notNullValue()));
    assertThrows(IllegalArgumentException.class, () -> ResumptionTokens.decode(token));
  }

  private static String base64Url(String json) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }
}
