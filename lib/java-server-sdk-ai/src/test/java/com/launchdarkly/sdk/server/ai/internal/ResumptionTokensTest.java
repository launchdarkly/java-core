package com.launchdarkly.sdk.server.ai.internal;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class ResumptionTokensTest {
  private static String decodeJson(String token) {
    return new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
  }

  @Test
  public void encodesCanonicalJsonInFixedOrder() {
    String token = ResumptionTokens.encode("run-1", "my-config", "abc123", 3, null);
    assertEquals("{\"runId\":\"run-1\",\"configKey\":\"my-config\",\"variationKey\":\"abc123\",\"version\":3}",
        decodeJson(token));
  }

  @Test
  public void omitsEmptyVariationKeyAndGraphKey() {
    String token = ResumptionTokens.encode("run-1", "my-config", "", 7, null);
    assertEquals("{\"runId\":\"run-1\",\"configKey\":\"my-config\",\"version\":7}", decodeJson(token));
  }

  @Test
  public void includesGraphKeyWhenPresent() {
    String token = ResumptionTokens.encode("run-1", "my-config", "v1", 2, "graph-1");
    assertEquals(
        "{\"runId\":\"run-1\",\"configKey\":\"my-config\",\"variationKey\":\"v1\",\"version\":2,\"graphKey\":\"graph-1\"}",
        decodeJson(token));
  }

  @Test
  public void roundTripsAllFields() {
    String token = ResumptionTokens.encode("run-1", "my-config", "v1", 2, "graph-1");
    ResumptionTokens.Data data = ResumptionTokens.decode(token);
    assertEquals("run-1", data.getRunId());
    assertEquals("my-config", data.getConfigKey());
    assertEquals("v1", data.getVariationKey());
    assertEquals(2, data.getVersion());
    assertEquals("graph-1", data.getGraphKey());
  }

  @Test
  public void decodeMissingVariationKeyYieldsEmptyAndNullGraph() {
    ResumptionTokens.Data data = ResumptionTokens.decode(ResumptionTokens.encode("r", "c", "", 1, null));
    assertEquals("", data.getVariationKey());
    assertNull(data.getGraphKey());
  }

  @Test
  public void decodeRejectsMalformedToken() {
    try {
      ResumptionTokens.decode("!!!not-base64!!!");
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  @Test
  public void decodeRejectsMissingRequiredField() {
    String token = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("{\"configKey\":\"c\",\"version\":1}".getBytes(StandardCharsets.UTF_8));
    try {
      ResumptionTokens.decode(token);
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
      assertEquals("Invalid resumption token: missing required field 'runId'", expected.getMessage());
    }
  }
}
