package com.launchdarkly.sdk.server.ai.internal;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Encodes and decodes resumption tokens for {@link LDAIConfigTrackerImpl}.
 * <p>
 * A resumption token is a URL-safe Base64 (RFC 4648, no padding) encoding of a canonical JSON
 * object that carries the run's identity fields. Tokens can be stored by callers and passed back
 * to {@link com.launchdarkly.sdk.server.ai.LDAIClient#createTracker} to reconstruct a tracker
 * across requests (for example, in a streaming or multi-turn scenario).
 * <p>
 * This class is an internal implementation detail and is not part of the supported API.
 */
public final class ResumptionTokens {
  private static final int MAX_TOKEN_BYTES = 4096;
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private ResumptionTokens() {
  }

  /**
   * Encodes a resumption token from the given run identity fields.
   * <p>
   * Field order in the JSON: {@code runId}, {@code configKey}, {@code variationKey} (omitted if
   * {@code null}), {@code version}, {@code graphKey} (omitted if {@code null}).
   *
   * @param runId the run ID
   * @param configKey the AI Config key
   * @param variationKey the variation key, or {@code null} to omit
   * @param version the config version
   * @param graphKey the graph key, or {@code null} to omit
   * @return the URL-safe Base64-encoded token
   */
  static String encode(String runId, String configKey, String variationKey,
      int version, String graphKey) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"runId\":\"").append(escapeJson(runId)).append('"');
    sb.append(",\"configKey\":\"").append(escapeJson(configKey)).append('"');
    if (variationKey != null) {
      sb.append(",\"variationKey\":\"").append(escapeJson(variationKey)).append('"');
    }
    sb.append(",\"version\":").append(version);
    if (graphKey != null) {
      sb.append(",\"graphKey\":\"").append(escapeJson(graphKey)).append('"');
    }
    sb.append('}');
    return ENCODER.encodeToString(sb.toString().getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Decodes a resumption token previously produced by {@link #encode}.
   *
   * @param token the URL-safe Base64 token
   * @return the decoded fields
   * @throws IllegalArgumentException if the token is malformed, oversized, or missing required fields
   */
  static Decoded decode(String token) {
    if (token == null) {
      throw new IllegalArgumentException("Resumption token must not be null");
    }
    if (token.length() > MAX_TOKEN_BYTES) {
      throw new IllegalArgumentException("Resumption token exceeds maximum length of " + MAX_TOKEN_BYTES + " bytes");
    }

    String json;
    try {
      byte[] bytes = DECODER.decode(token);
      json = new String(bytes, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Resumption token is not valid Base64: " + e.getMessage(), e);
    }

    return parseJson(json);
  }

  /**
   * Minimal JSON parser for the fixed token structure. Handles only the fields we write.
   */
  private static Decoded parseJson(String json) {
    json = json.trim();
    if (!json.startsWith("{") || !json.endsWith("}")) {
      throw new IllegalArgumentException("Resumption token JSON must be an object");
    }

    String runId = null;
    String configKey = null;
    String variationKey = null;
    Integer version = null;
    String graphKey = null;

    // Walk through the JSON object fields
    int pos = 1; // skip opening '{'
    while (pos < json.length() - 1) {
      pos = skipWhitespace(json, pos);
      if (pos >= json.length() - 1) {
        break;
      }
      if (json.charAt(pos) == ',') {
        pos++;
        pos = skipWhitespace(json, pos);
      }
      if (pos >= json.length() - 1) {
        break;
      }

      // Read key
      if (json.charAt(pos) != '"') {
        throw new IllegalArgumentException("Expected '\"' at position " + pos + " in resumption token");
      }
      int[] keyEnd = new int[1];
      String key = readString(json, pos, keyEnd);
      pos = keyEnd[0];

      pos = skipWhitespace(json, pos);
      if (pos >= json.length() || json.charAt(pos) != ':') {
        throw new IllegalArgumentException("Expected ':' after key in resumption token");
      }
      pos++; // skip ':'
      pos = skipWhitespace(json, pos);

      // Read value
      if (json.charAt(pos) == '"') {
        int[] valEnd = new int[1];
        String value = readString(json, pos, valEnd);
        pos = valEnd[0];
        switch (key) {
          case "runId": runId = value; break;
          case "configKey": configKey = value; break;
          case "variationKey": variationKey = value; break;
          case "graphKey": graphKey = value; break;
          default: break;
        }
      } else {
        // numeric value
        int start = pos;
        while (pos < json.length() && json.charAt(pos) != ',' && json.charAt(pos) != '}') {
          pos++;
        }
        String numStr = json.substring(start, pos).trim();
        if ("version".equals(key)) {
          try {
            version = Integer.parseInt(numStr);
          } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Field 'version' must be an integer in resumption token", e);
          }
        }
      }
    }

    if (runId == null) {
      throw new IllegalArgumentException("Resumption token missing required field 'runId'");
    }
    if (configKey == null) {
      throw new IllegalArgumentException("Resumption token missing required field 'configKey'");
    }
    if (version == null) {
      throw new IllegalArgumentException("Resumption token missing required field 'version'");
    }

    return new Decoded(runId, configKey, variationKey, version, graphKey);
  }

  private static int skipWhitespace(String s, int pos) {
    while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
      pos++;
    }
    return pos;
  }

  /**
   * Reads a JSON string starting at {@code pos} (which must point to the opening {@code "}).
   * Populates {@code end[0]} with the position after the closing {@code "}.
   */
  private static String readString(String s, int pos, int[] end) {
    if (s.charAt(pos) != '"') {
      throw new IllegalArgumentException("Expected '\"' at position " + pos);
    }
    pos++; // skip opening quote
    StringBuilder sb = new StringBuilder();
    while (pos < s.length()) {
      char c = s.charAt(pos);
      if (c == '"') {
        end[0] = pos + 1;
        return sb.toString();
      } else if (c == '\\') {
        pos++;
        if (pos >= s.length()) {
          throw new IllegalArgumentException("Unterminated escape in resumption token");
        }
        char escaped = s.charAt(pos);
        switch (escaped) {
          case '"': sb.append('"'); break;
          case '\\': sb.append('\\'); break;
          case '/': sb.append('/'); break;
          case 'b': sb.append('\b'); break;
          case 'f': sb.append('\f'); break;
          case 'n': sb.append('\n'); break;
          case 'r': sb.append('\r'); break;
          case 't': sb.append('\t'); break;
          case 'u':
            if (pos + 4 >= s.length()) {
              throw new IllegalArgumentException("Incomplete Unicode escape in resumption token");
            }
            String hex = s.substring(pos + 1, pos + 5);
            try {
              sb.append((char) Integer.parseInt(hex, 16));
            } catch (NumberFormatException e) {
              throw new IllegalArgumentException("Invalid Unicode escape \\u" + hex + " in resumption token");
            }
            pos += 4;
            break;
          default:
            throw new IllegalArgumentException("Unknown escape character '\\" + escaped + "' in resumption token");
        }
      } else {
        sb.append(c);
      }
      pos++;
    }
    throw new IllegalArgumentException("Unterminated string in resumption token");
  }

  /**
   * Escapes a string for inclusion in a JSON string literal. Handles the characters required by
   * RFC 8259 §7.
   */
  static String escapeJson(String s) {
    if (s == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"': sb.append("\\\""); break;
        case '\\': sb.append("\\\\"); break;
        case '\b': sb.append("\\b"); break;
        case '\f': sb.append("\\f"); break;
        case '\n': sb.append("\\n"); break;
        case '\r': sb.append("\\r"); break;
        case '\t': sb.append("\\t"); break;
        default:
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
      }
    }
    return sb.toString();
  }

  /**
   * Encodes a graph-level resumption token from the given graph identity fields.
   * <p>
   * Field order in the JSON: {@code runId}, {@code graphKey}, {@code variationKey} (omitted if
   * {@code null}), {@code version}.
   *
   * @param runId the run ID
   * @param graphKey the agent graph key
   * @param variationKey the variation key, or {@code null} to omit
   * @param version the graph version
   * @return the URL-safe Base64-encoded token
   */
  public static String encodeGraph(String runId, String graphKey, String variationKey, int version) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"runId\":\"").append(escapeJson(runId)).append('"');
    sb.append(",\"graphKey\":\"").append(escapeJson(graphKey)).append('"');
    if (variationKey != null) {
      sb.append(",\"variationKey\":\"").append(escapeJson(variationKey)).append('"');
    }
    sb.append(",\"version\":").append(version);
    sb.append('}');
    return ENCODER.encodeToString(sb.toString().getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Decodes a graph resumption token previously produced by {@link #encodeGraph}.
   *
   * @param token the URL-safe Base64 token
   * @return the decoded fields
   * @throws IllegalArgumentException if the token is malformed, oversized, or missing required fields
   */
  public static DecodedGraph decodeGraph(String token) {
    if (token == null) {
      throw new IllegalArgumentException("Graph resumption token must not be null");
    }
    if (token.length() > MAX_TOKEN_BYTES) {
      throw new IllegalArgumentException("Graph resumption token exceeds maximum length of " + MAX_TOKEN_BYTES + " bytes");
    }

    String json;
    try {
      byte[] bytes = DECODER.decode(token);
      json = new String(bytes, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Graph resumption token is not valid Base64: " + e.getMessage(), e);
    }

    return parseGraphJson(json);
  }

  private static DecodedGraph parseGraphJson(String json) {
    json = json.trim();
    if (!json.startsWith("{") || !json.endsWith("}")) {
      throw new IllegalArgumentException("Graph resumption token JSON must be an object");
    }

    String runId = null;
    String graphKey = null;
    String variationKey = null;
    Integer version = null;

    int pos = 1;
    while (pos < json.length() - 1) {
      pos = skipWhitespace(json, pos);
      if (pos >= json.length() - 1) {
        break;
      }
      if (json.charAt(pos) == ',') {
        pos++;
        pos = skipWhitespace(json, pos);
      }
      if (pos >= json.length() - 1) {
        break;
      }

      if (json.charAt(pos) != '"') {
        throw new IllegalArgumentException("Expected '\"' at position " + pos + " in graph resumption token");
      }
      int[] keyEnd = new int[1];
      String key = readString(json, pos, keyEnd);
      pos = keyEnd[0];

      pos = skipWhitespace(json, pos);
      if (pos >= json.length() || json.charAt(pos) != ':') {
        throw new IllegalArgumentException("Expected ':' after key in graph resumption token");
      }
      pos++;
      pos = skipWhitespace(json, pos);

      if (json.charAt(pos) == '"') {
        int[] valEnd = new int[1];
        String value = readString(json, pos, valEnd);
        pos = valEnd[0];
        switch (key) {
          case "runId": runId = value; break;
          case "graphKey": graphKey = value; break;
          case "variationKey": variationKey = value; break;
          default: break;
        }
      } else {
        int start = pos;
        while (pos < json.length() && json.charAt(pos) != ',' && json.charAt(pos) != '}') {
          pos++;
        }
        String numStr = json.substring(start, pos).trim();
        if ("version".equals(key)) {
          try {
            version = Integer.parseInt(numStr);
          } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Field 'version' must be an integer in graph resumption token", e);
          }
        }
      }
    }

    if (runId == null) {
      throw new IllegalArgumentException("Graph resumption token missing required field 'runId'");
    }
    if (graphKey == null) {
      throw new IllegalArgumentException("Graph resumption token missing required field 'graphKey'");
    }
    if (version == null) {
      throw new IllegalArgumentException("Graph resumption token missing required field 'version'");
    }

    return new DecodedGraph(runId, graphKey, variationKey, version);
  }

  /**
   * The decoded fields from a graph resumption token.
   */
  public static final class DecodedGraph {
    private final String runId;
    private final String graphKey;
    private final String variationKey;
    private final int version;

    public DecodedGraph(String runId, String graphKey, String variationKey, int version) {
      this.runId = runId;
      this.graphKey = graphKey;
      this.variationKey = variationKey;
      this.version = version;
    }

    public String getRunId() {
      return runId;
    }

    public String getGraphKey() {
      return graphKey;
    }

    public String getVariationKey() {
      return variationKey;
    }

    public int getVersion() {
      return version;
    }
  }

  /**
   * The decoded fields from a resumption token.
   */
  static final class Decoded {
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

    String getRunId() {
      return runId;
    }

    String getConfigKey() {
      return configKey;
    }

    String getVariationKey() {
      return variationKey;
    }

    int getVersion() {
      return version;
    }

    String getGraphKey() {
      return graphKey;
    }
  }
}
