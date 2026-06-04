package com.launchdarkly.sdk.server.ai.internal;

import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.LDValueType;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Internal codec for AI Config tracker resumption tokens.
 * <p>
 * A resumption token is the URL-safe Base64 (RFC 4648, no padding) encoding of a canonical JSON
 * object whose keys appear in a fixed order: {@code runId}, {@code configKey}, {@code variationKey}
 * (omitted when absent), {@code version}, {@code graphKey} (omitted when absent). The JSON is
 * serialized with no extraneous whitespace so that tokens are stable across SDKs.
 * <p>
 * This class is for internal use only and is not part of the supported public API.
 */
public final class ResumptionTokens {
  private ResumptionTokens() {
  }

  /**
   * Decoded contents of a resumption token.
   */
  public static final class Data {
    private final String runId;
    private final String configKey;
    private final String variationKey;
    private final int version;
    private final String graphKey;

    public Data(String runId, String configKey, String variationKey, int version, String graphKey) {
      this.runId = runId;
      this.configKey = configKey;
      this.variationKey = variationKey;
      this.version = version;
      this.graphKey = graphKey;
    }

    public String getRunId() {
      return runId;
    }

    public String getConfigKey() {
      return configKey;
    }

    public String getVariationKey() {
      return variationKey;
    }

    public int getVersion() {
      return version;
    }

    public String getGraphKey() {
      return graphKey;
    }
  }

  /**
   * Encodes tracker metadata into a resumption token.
   *
   * @param runId the tracker's run id (required)
   * @param configKey the configuration key (required)
   * @param variationKey the variation key, or {@code null}/empty to omit
   * @param version the variation version
   * @param graphKey the containing graph key, or {@code null}/empty to omit
   * @return the URL-safe Base64-encoded token
   */
  public static String encode(String runId, String configKey, String variationKey, int version, String graphKey) {
    StringBuilder json = new StringBuilder();
    json.append('{');
    appendStringField(json, "runId", runId);
    json.append(',');
    appendStringField(json, "configKey", configKey);
    if (variationKey != null && !variationKey.isEmpty()) {
      json.append(',');
      appendStringField(json, "variationKey", variationKey);
    }
    json.append(',');
    json.append("\"version\":").append(version);
    if (graphKey != null && !graphKey.isEmpty()) {
      json.append(',');
      appendStringField(json, "graphKey", graphKey);
    }
    json.append('}');

    byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /**
   * Decodes a resumption token.
   *
   * @param token the token string
   * @return the decoded data
   * @throws IllegalArgumentException if the token cannot be decoded or is missing a required field
   */
  public static Data decode(String token) {
    if (token == null) {
      throw new IllegalArgumentException("Invalid resumption token: token is null");
    }

    String json;
    try {
      byte[] decoded = Base64.getUrlDecoder().decode(token);
      json = new String(decoded, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid resumption token: " + e.getMessage(), e);
    }

    LDValue payload;
    try {
      payload = LDValue.parse(json);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Invalid resumption token: malformed JSON", e);
    }
    if (payload.getType() != LDValueType.OBJECT) {
      throw new IllegalArgumentException("Invalid resumption token: payload is not an object");
    }

    requireField(payload, "runId");
    requireField(payload, "configKey");
    requireField(payload, "version");

    String variationKey = payload.get("variationKey").isNull() ? "" : payload.get("variationKey").stringValue();
    String graphKey = payload.get("graphKey").isNull() ? null : payload.get("graphKey").stringValue();

    return new Data(
        payload.get("runId").stringValue(),
        payload.get("configKey").stringValue(),
        variationKey,
        payload.get("version").intValue(),
        graphKey);
  }

  private static void requireField(LDValue payload, String field) {
    if (payload.get(field).isNull()) {
      throw new IllegalArgumentException("Invalid resumption token: missing required field '" + field + "'");
    }
  }

  private static void appendStringField(StringBuilder json, String key, String value) {
    json.append('"').append(key).append("\":");
    appendJsonString(json, value);
  }

  private static void appendJsonString(StringBuilder json, String value) {
    json.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"':
          json.append("\\\"");
          break;
        case '\\':
          json.append("\\\\");
          break;
        case '\n':
          json.append("\\n");
          break;
        case '\r':
          json.append("\\r");
          break;
        case '\t':
          json.append("\\t");
          break;
        default:
          if (c < 0x20) {
            json.append(String.format("\\u%04x", (int) c));
          } else {
            json.append(c);
          }
      }
    }
    json.append('"');
  }
}
