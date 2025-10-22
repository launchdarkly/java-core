package com.launchdarkly.testhelpers;

import com.google.gson.Gson;
import com.google.gson.JsonElement;

/**
 * A simple wrapper for a string that can be parsed as JSON for tests.
 * <p>
 * This class provides strong typing so that it is clear when test matchers apply to JSON
 * values versus strings, and hides the implementation details of parsing and serialization
 * which are not relevant to the test logic.
 * 
 * @see JsonAssertions
 * @since 1.1.0
 */
public final class JsonTestValue {
  private static final Gson gson = new Gson();
  
  final String raw;
  final JsonElement parsed;
  
  private JsonTestValue(String raw, JsonElement parsed) {
    this.raw = raw;
    this.parsed = parsed;
  }
  
  /**
   * Creates a {@code JsonTestValue} from a string that should contain JSON.
   * <p>
   * This method fails immediately for any string that is not well-formed JSON. However, if
   * it is a null reference, it returns an "undefined" instance that will return {@code false}
   * from {@link #isDefined()}.
   * 
   * @param raw the input string
   * @return a {@code JsonTestValue}
   * @throws AssertionError for malformed JSON
   */
  public static JsonTestValue jsonOf(String raw) {
    if (raw == null) {
      return new JsonTestValue(null, null);
    }
    try {
      return new JsonTestValue(raw, gson.fromJson(raw, JsonElement.class));
    } catch (Exception e) {
      throw new AssertionError("not valid JSON (" + e + "): " + raw);
    }
  }
  
  static JsonTestValue ofParsed(JsonElement json) {
    return new JsonTestValue(json == null ? null : gson.toJson(json), json);
  }
  
  /**
   * Creates a {@code JsonTestValue} by serializing an arbitrary value to JSON. For
   * instance, {@code jsonFromValue(true)} is equivalent to {@code jsonOf("true")}.
   * 
   * @param value an arbitrary value
   * @return a {@code JsonTestValue}
   */
  public static JsonTestValue jsonFromValue(Object value) {
    return ofParsed(gson.toJsonTree(value));
  }
  
  @Override
  public String toString() {
    return raw == null ? "<no value>" : raw;
  }
  
  /**
   * Returns true if there is a value (that is, the original string was not a null reference).
   * 
   * @return true if defined
   */
  public boolean isDefined() {
    return raw != null;
  }
}
