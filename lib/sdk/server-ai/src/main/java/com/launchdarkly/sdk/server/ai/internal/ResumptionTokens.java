package com.launchdarkly.sdk.server.ai.internal;

import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.LDValueType;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Encodes and decodes AI run resumption tokens.
 * <p>
 * A token is the URL-safe Base64 (no padding) of a canonical JSON object whose keys appear in a
 * fixed order: {@code runId, configKey, variationKey, version}, followed by {@code graphKey} only
 * when it is set. {@code variationKey} is always present (empty string when unknown) so the encoding
 * is byte-compatible with the other LaunchDarkly SDKs. {@code modelName} and {@code providerName}
 * are intentionally not carried; a tracker reconstructed from a token reports them as empty strings.
 * <p>
 * Decoding is strict: each field is type-validated, and malformed or oversized tokens are rejected
 * with an {@link IllegalArgumentException}.
 * <p>
 * This class is an internal implementation detail and is not part of the supported API.
 */
public final class ResumptionTokens {
  /**
   * Maximum size, in bytes, of a token's decoded JSON payload. Anything larger is rejected to bound
   * the work done parsing untrusted input.
   */
  static final int MAX_PAYLOAD_BYTES = 4096;

  private ResumptionTokens() {
  }

  /**
   * Encodes a resumption token.
   *
   * @param runId the per-run UUID
   * @param configKey the AI Config key
   * @param variationKey the variation key; encoded as empty string when {@code null}
   * @param version the AI Config version
   * @param graphKey the graph key, or {@code null} to omit it
   * @return the URL-safe Base64 token
   */
  public static String encode(
      String runId, String configKey, String variationKey, int version, String graphKey) {
    StringBuilder json = new StringBuilder(96);
    json.append("{\"runId\":").append(jsonString(runId))
        .append(",\"configKey\":").append(jsonString(configKey))
        .append(",\"variationKey\":").append(jsonString(variationKey == null ? "" : variationKey))
        .append(",\"version\":").append(version);
    if (graphKey != null) {
      json.append(",\"graphKey\":").append(jsonString(graphKey));
    }
    json.append('}');
    return Base64.getUrlEncoder().withoutPadding()
        .encodeToString(json.toString().getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Decodes and validates a resumption token.
   *
   * @param token the token to decode
   * @return the decoded fields
   * @throws IllegalArgumentException if the token is {@code null}, oversized, not valid Base64, not
   *     a JSON object, or missing/mistyped required fields
   */
  public static Decoded decode(String token) {
    if (token == null) {
      throw new IllegalArgumentException("resumption token must not be null");
    }
    byte[] bytes;
    try {
      bytes = Base64.getUrlDecoder().decode(token);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("malformed resumption token: invalid Base64", e);
    }
    if (bytes.length > MAX_PAYLOAD_BYTES) {
      throw new IllegalArgumentException(
          "malformed resumption token: payload exceeds " + MAX_PAYLOAD_BYTES + " bytes");
    }

    LDValue payload;
    try {
      payload = LDValue.parse(new String(bytes, StandardCharsets.UTF_8));
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("malformed resumption token: invalid JSON", e);
    }
    if (payload.getType() != LDValueType.OBJECT) {
      throw new IllegalArgumentException("malformed resumption token: expected a JSON object");
    }

    String runId = requireString(payload, "runId");
    String configKey = requireString(payload, "configKey");
    String variationKey = optionalString(payload, "variationKey", "");
    int version = requireInt(payload, "version");
    String graphKey = optionalString(payload, "graphKey", null);
    return new Decoded(runId, configKey, variationKey, version, graphKey);
  }

  private static String requireString(LDValue object, String field) {
    LDValue v = object.get(field);
    if (!v.isString()) {
      throw new IllegalArgumentException(
          "malformed resumption token: field '" + field + "' must be a string");
    }
    return v.stringValue();
  }

  private static String optionalString(LDValue object, String field, String defaultValue) {
    LDValue v = object.get(field);
    if (v.getType() == LDValueType.NULL) {
      return defaultValue;
    }
    if (!v.isString()) {
      throw new IllegalArgumentException(
          "malformed resumption token: field '" + field + "' must be a string");
    }
    return v.stringValue();
  }

  private static int requireInt(LDValue object, String field) {
    LDValue v = object.get(field);
    if (v.getType() != LDValueType.NUMBER || !v.isInt()) {
      throw new IllegalArgumentException(
          "malformed resumption token: field '" + field + "' must be an integer");
    }
    return v.intValue();
  }

  /**
   * Escapes a Java string as a JSON string literal using the standard JSON escapes (matching
   * {@code JSON.stringify}), so the encoding stays byte-compatible across SDKs.
   */
  private static String jsonString(String s) {
    StringBuilder sb = new StringBuilder(s.length() + 2);
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"':
          sb.append("\\\"");
          break;
        case '\\':
          sb.append("\\\\");
          break;
        case '\b':
          sb.append("\\b");
          break;
        case '\f':
          sb.append("\\f");
          break;
        case '\n':
          sb.append("\\n");
          break;
        case '\r':
          sb.append("\\r");
          break;
        case '\t':
          sb.append("\\t");
          break;
        default:
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
          break;
      }
    }
    sb.append('"');
    return sb.toString();
  }

  /**
   * The validated fields decoded from a resumption token.
   */
  public static final class Decoded {
    private final String runId;
    private final String configKey;
    private final String variationKey;
    private final int version;
    private final String graphKey;

    Decoded(String runId, String configKey, String variationKey, int version, String graphKey) {
      this.runId = runId;
      this.configKey = configKey;
      this.variationKey = variationKey;
      this.version = version;
      this.graphKey = graphKey;
    }

    /**
     * Returns the per-run UUID.
     *
     * @return the run id
     */
    public String getRunId() {
      return runId;
    }

    /**
     * Returns the AI Config key.
     *
     * @return the config key
     */
    public String getConfigKey() {
      return configKey;
    }

    /**
     * Returns the variation key.
     *
     * @return the variation key, never {@code null}
     */
    public String getVariationKey() {
      return variationKey;
    }

    /**
     * Returns the AI Config version.
     *
     * @return the version
     */
    public int getVersion() {
      return version;
    }

    /**
     * Returns the graph key.
     *
     * @return the graph key, or {@code null} when absent
     */
    public String getGraphKey() {
      return graphKey;
    }
  }
}
