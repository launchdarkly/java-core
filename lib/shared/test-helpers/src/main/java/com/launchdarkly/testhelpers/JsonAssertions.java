package com.launchdarkly.testhelpers;

import com.google.common.base.Joiner;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.launchdarkly.testhelpers.JsonTestValue.jsonFromValue;
import static com.launchdarkly.testhelpers.JsonTestValue.jsonOf;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Test assertions and matchers related to JSON.
 * <p>
 * The {@code assert} methods here provide simple assertions for strings that are assumed
 * to contain JSON.
 * <p>
 * The other methods are factories for type-safe Hamcrest matchers. These are much more
 * flexible, as you can use standard Hamcrest combinators like {@code allOf} or {@code not}.
 * These use {@link JsonTestValue} as their type parameter, to prevent confusion between
 * test code that operates on JSON strings and test code that operates on other kinds of
 * strings. {@link JsonTestValue} is easily convertible from strings or other types;
 * see {@link JsonTestValue#jsonOf(String)} and {@link JsonTestValue#jsonFromValue(Object)}.
 * <p>
 * Examples:
 * <pre><code>
 *     // check for the exact JSON properties {"a": 1, "b": 2} in any order
 *     assertThat(jsonOf(myString), jsonEquals("{\"a\":1, \"b\": 2}");
 *     
 *     // check that a JSON object's property "p" is equal to a specific boolean value
 *     assertThat(jsonOf(myString), jsonProperty("p", someBooleanValue));
 *     
 *     // check that a JSON object's property "p" is either null or omitted
 *     assertThat(jsonOf(myString),
 *         jsonProperty("p", anyOf(jsonNull(), jsonUndefined())));
 *     
 *     // check that a JSON object's property "p" is an array containing a specific value
 *     assertThat(jsonOf(myString),
 *         jsonProperty("p", isJsonArray(hasItem(jsonEqualsValue(someValue)))));
 * </code></pre>
 * <p>
 * When comparing unequal JSON objects or arrays, these methods will do their best to
 * show you a localized difference such as a specific property, rather than only showing
 * the entire actual and expected values. 
 *    
 * @since 1.1.0
 */
public abstract class JsonAssertions {
  /**
   * Parses two strings as JSON and compares them for deep equality. If they are unequal,
   * it tries to describe the difference as specifically as possible by recursing into
   * object properties or array elements.
   * 
   * @param expected the expected JSON string
   * @param actual the actual JSON string
   * @throws AssertionError if the values are not deeply equal, or are not valid JSON
   */
  public static void assertJsonEquals(String expected, String actual) {
    assertThat(jsonOf(actual), jsonEquals(jsonOf(expected)));
  }
  
  /**
   * Equivalent to {@link #assertJsonEquals(String, String)}, but as a typed matcher.
   * 
   * @param expected the expected JSON value
   * @return a matcher
   */
  public static Matcher<JsonTestValue> jsonEquals(final JsonTestValue expected) {
    checkNotNull(expected, "expected");
    return new TypeSafeDiagnosingMatcher<JsonTestValue>() {
      @Override
      public void describeTo(Description description) {
        description.appendText("JSON is equal to: " + expected);
      }

      @Override
      protected boolean matchesSafely(JsonTestValue actual, Description mismatchDescription) {
        if (!actual.isDefined()) {
          if (!expected.isDefined()) {
            return true;
          }
          mismatchDescription.appendValue(actual);
          return false;
        }
        if (!expected.isDefined()) {
          mismatchDescription.appendValue(expected);
          return false;
        }
        if (actual.parsed.equals(expected.parsed)) {
          return true;
        }
        String diff = describeJsonDifference(expected.parsed, actual.parsed, "", false);
        if (diff == null) {
          diff = "expected: " + expected + "\nactual: " + actual.raw;
        } else {
          diff = diff + "\nfull JSON was: " + actual.raw;
        }
        mismatchDescription.appendText(diff);
        return false;
      }
    };
  }
  
  /**
   * Equivalent to {@code jsonEquals(JsonTestValue.jsonOf(expected))}.
   * 
   * @param expected the expected JSON as a string
   * @return a matcher
   */
  public static Matcher<JsonTestValue> jsonEquals(String expected) {
    return jsonEquals(jsonOf(expected));
  }

  /**
   * Equivalent to {@code jsonEquals(JsonTestValue.jsonFromValue(expected))}.
   * 
   * @param expected a value that will be serialized to JSON and matched
   * @return a matcher
   */
  public static Matcher<JsonTestValue> jsonEqualsValue(Object expected) {
    return jsonEquals(jsonFromValue(expected));
  }
  
  /**
   * Same as {@link #assertJsonEquals(String, String)} except that it allows any JSON
   * objects in the actual data to contain extra properties that are not in the expected
   * data.
   * 
   * @param expected the expected JSON string
   * @param actual the actual JSON string
   * @throws AssertionError if the expected values are not a subset of the actual
   *   values, or if the strings are not valid JSON
   */
  public static void assertJsonIncludes(String expected, String actual) {
    assertThat(jsonOf(actual), jsonIncludes(expected));
  }
  
  /**
   * Equivalent to {@link #assertJsonIncludes(String, String)}, but as a Hamcrest matcher.
   * 
   * @param expected the expected JSON object properties
   * @return a string matcher
   */
  public static Matcher<JsonTestValue> jsonIncludes(JsonTestValue expected) {
    checkNotNull(expected, "expected");
    return new TypeSafeDiagnosingMatcher<JsonTestValue>() {
      @Override
      public void describeTo(Description description) {
        description.appendText("includes these JSON properties: " + expected);
      }

      @Override
      protected boolean matchesSafely(JsonTestValue actual, Description mismatchDescription) {
        if (!actual.isDefined()) {
          if (!expected.isDefined()) {
            return true;
          }
          mismatchDescription.appendValue(actual);
          return false;
        }
        if (!expected.isDefined()) {
          mismatchDescription.appendValue(expected);
          return false;
        }
        if (isJsonSubset(expected.parsed, actual.parsed)) {
          return true;
        }
        String diff = describeJsonDifference(expected.parsed, actual.parsed, "", true);
        if (diff == null) {
          diff = "expected: " + expected + "\nactual: " + actual.raw;
        } else {
          diff = diff + "\nfull JSON was: " + actual.raw;
        }
        mismatchDescription.appendText(diff);
        return false;
      }
    };
  }

  /**
   * Equivalent to {@code jsonIncludes(JsonTestValue.jsonOf(expected))}.
   * 
   * @param expected the expected JSON as a string
   * @return a matcher
   */
  public static Matcher<JsonTestValue> jsonIncludes(String expected) {
    return jsonIncludes(jsonOf(expected));
  }
  
  /**
   * A matcher that verifies that the input value is a JSON null. This is equivalent to
   * {@code jsonEquals(JsonTestValue.jsonOf("null"))}.
   * 
   * @return a matcher
   */
  public static Matcher<JsonTestValue> jsonNull() {
    return jsonEquals(jsonOf("null"));
  }

  /**
   * A matcher that verifies that the input value is completely undefined (as opposed to
   * being a JSON null).
   * 
   * @return a matcher
   */
  public static Matcher<JsonTestValue> jsonUndefined() {
    return new TypeSafeDiagnosingMatcher<JsonTestValue>() {
      @Override
      public void describeTo(Description description) {
        description.appendText("is undefined");
      }

      @Override
      protected boolean matchesSafely(JsonTestValue item, Description mismatchDescription) {
        if (item == null || !item.isDefined()) {
          return true;
        }
        mismatchDescription.appendText("had value: " + item.raw);
        return false;
      }
    };
  }

  /**
   * A matcher that verifies that the input value is an object that has a property with
   * the specified name, and that the property value matches the specified matcher.
   * 
   * @param name the property name
   * @param matcher a matcher for the property value
   * @return a matcher
   */

  public static Matcher<JsonTestValue> jsonProperty(final String name, final Matcher<JsonTestValue> matcher) {
    checkNotNull(name, "name");
    checkNotNull(matcher, "matcher");
    return new TypeSafeDiagnosingMatcher<JsonTestValue>() {
      @Override
      public void describeTo(Description description) {
        description.appendText(String.format("property \"%s\": ", name));
        matcher.describeTo(description);
      }

      @Override
      protected boolean matchesSafely(JsonTestValue actual, Description mismatchDescription) {
        if (!actual.isDefined()) {
          mismatchDescription.appendValue(actual);
          return false;
        }
        if (actual.parsed instanceof JsonObject) {
          JsonTestValue propValue = JsonTestValue.ofParsed(((JsonObject)actual.parsed).get(name));
          if (!matcher.matches(propValue)) {
            matcher.describeMismatch(propValue, mismatchDescription);
            return false;
          }
          return true;
        }
        mismatchDescription.appendText("not a JSON object: ").appendText(actual.raw);
        return false;
      }
    };
  }

  /**
   * A shortcut for using {@link #jsonProperty} with {@link #jsonEquals(JsonTestValue)}.
   * 
   * @param name a property name
   * @param value the desired value
   * @return a matcher
   */
  public static Matcher<JsonTestValue> jsonProperty(String name, JsonTestValue value) {
    return jsonProperty(name, jsonEquals(value));
  }
  
  /**
   * A shortcut for using {@link #jsonProperty(String, JsonTestValue)} with
   * {@link JsonTestValue#jsonFromValue(Object)}.
   * 
   * @param name a property name
   * @param value a value that will be converted to JSON
   * @return a matcher
   */
  public static Matcher<JsonTestValue> jsonProperty(String name, boolean value) {
    return jsonProperty(name, jsonFromValue(value));
  }
  
  /**
   * A shortcut for using {@link #jsonProperty(String, JsonTestValue)} with
   * {@link JsonTestValue#jsonFromValue(Object)}.
   * 
   * @param name a property name
   * @param value a value that will be converted to JSON
   * @return a matcher
   */
  public static Matcher<JsonTestValue> jsonProperty(String name, int value) {
    return jsonProperty(name, jsonFromValue(value));
  }
  
  /**
   * A shortcut for using {@link #jsonProperty(String, JsonTestValue)} with
   * {@link JsonTestValue#jsonFromValue(Object)}.
   * 
   * @param name a property name
   * @param value a value that will be converted to JSON
   * @return a matcher
   */
  public static Matcher<JsonTestValue> jsonProperty(String name, double value) {
    return jsonProperty(name, jsonFromValue(value));
  }

  /**
   * A shortcut for using {@link #jsonProperty(String, JsonTestValue)} with
   * {@link JsonTestValue#jsonFromValue(Object)}.
   * 
   * @param name a property name
   * @param value a value that will be converted to JSON
   * @return a matcher
   */
  public static Matcher<JsonTestValue> jsonProperty(String name, String value) {
    return jsonProperty(name, jsonFromValue(value));
  }
  
  /**
   * A matcher that verifies that the input value is an array whose elements match the
   * specified matchers.
   * 
   * @param elementsMatcher a matcher for the contents of the array
   * @return a matcher
   */
  public static Matcher<JsonTestValue> isJsonArray(final Matcher<Iterable<? extends JsonTestValue>> elementsMatcher) {
    checkNotNull(elementsMatcher, "elementsMatcher");
    return new TypeSafeDiagnosingMatcher<JsonTestValue>() {
      @Override
      public void describeTo(Description description) {
        description.appendText("JSON array: ");
        elementsMatcher.describeTo(description);
      }

      @Override
      protected boolean matchesSafely(JsonTestValue actual, Description mismatchDescription) {
        if (!actual.isDefined()) {
          mismatchDescription.appendValue(actual);
          return false;
        }
        if (actual.parsed instanceof JsonArray) {
          List<JsonTestValue> values = new ArrayList<>();
          for (JsonElement element: (JsonArray)actual.parsed) {
            values.add(JsonTestValue.ofParsed(element));
          }
          if (!elementsMatcher.matches(values)) {
            elementsMatcher.describeMismatch(values, mismatchDescription);
            return false;
          }
          return true;
        }
        mismatchDescription.appendText("not a JSON array: ").appendText(actual.raw);
        return false;
      }
    };
  }
  
  private static boolean isJsonSubset(JsonElement expected, JsonElement actual) {
    if (expected instanceof JsonObject && actual instanceof JsonObject) {
      JsonObject eo = (JsonObject)expected, ao = (JsonObject)actual;
      for (Map.Entry<String, JsonElement> e: eo.entrySet()) {
        if (!ao.has(e.getKey()) || !isJsonSubset(e.getValue(), ao.get(e.getKey()))) {
          return false;
        }
      }
      return true;
    }
    if (expected instanceof JsonArray && actual instanceof JsonArray) {
      JsonArray ea = (JsonArray)expected, aa = (JsonArray)actual;
      if (ea.size() != aa.size()) {
        return false;
      }
      for (int i = 0; i < ea.size(); i++) {
        if (!isJsonSubset(ea.get(i), aa.get(i))) {
          return false;
        }
      }
      return true;
    }
    return actual.equals(expected);
  }
  
  private static String describeJsonDifference(
      JsonElement expected,
      JsonElement actual,
      String prefix,
      boolean allowExtraProps
      ) {
    if (actual instanceof JsonObject && expected instanceof JsonObject) {
      return describeJsonObjectDifference((JsonObject)expected, (JsonObject)actual, prefix, allowExtraProps);
    }
    if (actual instanceof JsonArray && expected instanceof JsonArray) {
      return describeJsonArrayDifference((JsonArray)expected, (JsonArray)actual, prefix, allowExtraProps);
    }
    return null;
  }

  private static String describeJsonObjectDifference(
      JsonObject expected,
      JsonObject actual,
      String prefix,
      boolean allowExtraProps
      ) {
    List<String> diffs = new ArrayList<>();
    Set<String> allKeys = new HashSet<>();
    for (Map.Entry<String, JsonElement> e: expected.entrySet()) {
      allKeys.add(e.getKey());
    }
    for (Map.Entry<String, JsonElement> e: actual.entrySet()) {
      allKeys.add(e.getKey());
    }
    for (String key: allKeys) {
      String prefixedKey = prefix + (prefix == "" ? "" : ".") + key;
      String expectedDesc = null, actualDesc = null, detailDiff = null;
      if (expected.has(key)) {
        if (actual.has(key)) {
          JsonElement actualValue = actual.get(key), expectedValue = expected.get(key);
          if (!actualValue.equals(expectedValue)) {
            expectedDesc = expectedValue.toString();
            actualDesc = actualValue.toString();
            detailDiff = describeJsonDifference(expectedValue, actualValue, prefixedKey, allowExtraProps);
          }
        } else {
          expectedDesc = expected.get(key).toString();
          actualDesc = "<absent>";
        }
      } else if (!allowExtraProps) {
        actualDesc = actual.get(key).toString();
        expectedDesc = "<absent>";
      }
      if (expectedDesc != null || actualDesc != null) {
        if (detailDiff != null) {
          diffs.add(detailDiff);
        } else {
          diffs.add(String.format("at \"%s\": expected = %s, actual = %s", prefixedKey,
              expectedDesc, actualDesc));
        }
      }
    }
    return Joiner.on("\n").join(diffs);
  }

  private static String describeJsonArrayDifference(
      JsonArray expected,
      JsonArray actual,
      String prefix,
      boolean allowExtraProps
      ) {
    if (expected.size() != actual.size()) {
      return null; // can't provide a detailed diff, just show the whole values
    }
    List<String> diffs = new ArrayList<>();
    for (int i = 0; i < expected.size(); i++) {
      String prefixedIndex = String.format("%s[%d]", prefix, i);
      JsonElement actualValue = actual.get(i), expectedValue = expected.get(i);
      if (!actualValue.equals(expectedValue)) {
        String detailDiff = describeJsonDifference(expectedValue, actualValue, prefixedIndex, allowExtraProps);
        if (detailDiff != null) {
          diffs.add(detailDiff);
        } else {
          diffs.add(String.format("at \"%s\": expected = %s, actual = %s", prefixedIndex,
              expectedValue.toString(), actualValue.toString()));
        }
      }
    }
    return Joiner.on("\n").join(diffs);
  }
}
