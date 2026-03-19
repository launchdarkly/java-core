package com.launchdarkly.testhelpers;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.hamcrest.TypeSafeDiagnosingMatcher;
import org.junit.Test;

import static com.launchdarkly.testhelpers.JsonAssertions.assertJsonEquals;
import static com.launchdarkly.testhelpers.JsonAssertions.assertJsonIncludes;
import static com.launchdarkly.testhelpers.JsonAssertions.isJsonArray;
import static com.launchdarkly.testhelpers.JsonAssertions.jsonEquals;
import static com.launchdarkly.testhelpers.JsonAssertions.jsonEqualsValue;
import static com.launchdarkly.testhelpers.JsonAssertions.jsonIncludes;
import static com.launchdarkly.testhelpers.JsonAssertions.jsonNull;
import static com.launchdarkly.testhelpers.JsonAssertions.jsonProperty;
import static com.launchdarkly.testhelpers.JsonAssertions.jsonUndefined;
import static com.launchdarkly.testhelpers.JsonTestValue.jsonOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyIterable;

@SuppressWarnings("javadoc")
public class JsonAssertionsTest {
  @Test
  public void assertJsonEqualsSuccess() {
    jsonEqualsShouldSucceed("null", "null");
    jsonEqualsShouldSucceed("true", "true");
    jsonEqualsShouldSucceed("1", "1");
    jsonEqualsShouldSucceed("\"x\"", "\"x\"");
    jsonEqualsShouldSucceed("{\"a\":1,\"b\":{\"c\":2}}", "{\"b\":{\"c\":2},\"a\":1}");
    jsonEqualsShouldSucceed("[1,2,[3,4]]","[1,2,[3,4]]");
    
    assertThat(jsonOf("true"), jsonEqualsValue(true));
  }
  
  private static void jsonEqualsShouldSucceed(String expected, String actual) {
    assertJsonEquals(expected, actual);
    assertThat(jsonOf(actual), jsonEquals(jsonOf(expected)));
    assertThat(jsonOf(actual), jsonEquals(expected));
  }
  
  @Test
  public void assertJsonEqualsFailureWithNoDetailedDiff() {
    jsonEqualsShouldFail("null", null, "no value");
    jsonEqualsShouldFail("null", "{", "not valid JSON");
    jsonEqualsShouldFail("null", "true", "expected: null\nactual: true");
    jsonEqualsShouldFail("false", "true", "expected: false\nactual: true");
    jsonEqualsShouldFail("{\"a\":1}", "3", "expected: {\"a\":1}\nactual: 3");
    jsonEqualsShouldFail("[1,2]", "3", "expected: [1,2]\nactual: 3");
    jsonEqualsShouldFail("[1,2]", "[1,2,3]", "expected: [1,2]\nactual: [1,2,3]");
  }

  @Test
  public void assertJsonEqualsFailureWithDetailedDiff() {
    jsonEqualsShouldFail("{\"a\":1,\"b\":2}", "{\"a\":1,\"b\":3}",
        "at \"b\": expected = 2, actual = 3");

    jsonEqualsShouldFail("{\"a\":1,\"b\":2}", "{\"a\":1}",
        "at \"b\": expected = 2, actual = <absent>");

    jsonEqualsShouldFail("{\"a\":1}", "{\"a\":1,\"b\":2}",
        "at \"b\": expected = <absent>, actual = 2");

    jsonEqualsShouldFail("{\"a\":1,\"b\":{\"c\":2}}", "{\"a\":1,\"b\":{\"c\":3}}",
        "at \"b.c\": expected = 2, actual = 3");

    jsonEqualsShouldFail("{\"a\":1,\"b\":[2,3]}", "{\"a\":1,\"b\":[3,3]}",
        "at \"b[0]\": expected = 2, actual = 3");

    jsonEqualsShouldFail("[100,200,300]", "[100,201,300]",
        "at \"[1]\": expected = 200, actual = 201");

    jsonEqualsShouldFail("[100,[200,210],300]", "[100,[201,210],300]",
        "at \"[1][0]\": expected = 200, actual = 201");

    jsonEqualsShouldFail("[100,{\"a\":1},300]", "[100,{\"a\":2},300]",
        "at \"[1].a\": expected = 1, actual = 2");
  }
  
  private static void jsonEqualsShouldFail(String expected, String actual, String expectedMessage) {
    assertThat(() -> assertJsonEquals(expected, actual),
        shouldFailWithMessage(Matchers.containsString(expectedMessage)));
    assertThat(() -> assertThat(jsonOf(actual), jsonEquals(jsonOf(expected))),
        shouldFailWithMessage(Matchers.containsString(expectedMessage)));
  }
  
  @Test
  public void assertJsonIncludesSuccess() {
    jsonIncludesShouldSucceed("{\"a\":1,\"b\":2}", "{\"b\":2,\"a\":1}");
    jsonIncludesShouldSucceed("{\"a\":1,\"b\":2}", "{\"b\":2,\"a\":1,\"c\":3}");
    jsonIncludesShouldSucceed("{\"a\":1,\"b\":{\"c\":2}}", "{\"b\":{\"c\":2,\"d\":3},\"a\":1}");
  }

  private void jsonIncludesShouldSucceed(String expected, String actual) {
    assertJsonIncludes(expected, actual);
    assertThat(jsonOf(actual), jsonIncludes(jsonOf(expected)));
    assertThat(jsonOf(actual), jsonIncludes(expected));
  }
  
  @Test
  public void assertJsonIncludesFailure() {
    jsonIncludesShouldFail("null", null, "no value");
    jsonIncludesShouldFail("null", "{", "not valid JSON");

    jsonIncludesShouldFail("{\"a\":1}", "{\"a\":0,\"b\":2,\"c\":3}",
        "at \"a\": expected = 1, actual = 0");

    jsonIncludesShouldFail("{\"a\":1}", "{\"b\":2,\"c\":3}",
        "at \"a\": expected = 1, actual = <absent>");

    jsonIncludesShouldFail("{\"b\":2,\"a\":1,\"c\":3}", "{\"a\":1,\"b\":2}",
        "at \"c\": expected = 3, actual = <absent>");

    jsonIncludesShouldFail("{\"b\":{\"c\":2,\"d\":3},\"a\":1}", "{\"a\":1,\"b\":{\"c\":2}}",
        "at \"b.d\": expected = 3, actual = <absent>");
  }

  private static void jsonIncludesShouldFail(String expected, String actual, String expectedMessage) {
    assertThat(() -> assertJsonIncludes(expected, actual),
        shouldFailWithMessage(Matchers.containsString(expectedMessage)));
    assertThat(() -> assertThat(jsonOf(actual), jsonIncludes(expected)),
        shouldFailWithMessage(Matchers.containsString(expectedMessage)));
  }
  
  @Test
  public void jsonPropertySuccess() {
    assertThat(jsonOf("{\"a\":true}"), jsonProperty("a", jsonEquals("true")));
    assertThat(jsonOf("{\"a\":true}"), jsonProperty("a", true));

    assertThat(jsonOf("{\"a\":1}"), jsonProperty("a", 1));
    assertThat(jsonOf("{\"a\":2.5}"), jsonProperty("a", 2.5));
    assertThat(jsonOf("{\"a\":\"x\"}"), jsonProperty("a", "x"));

    assertThat(jsonOf("{\"a\":{\"b\": 1}}"), jsonProperty("a", jsonProperty("b", 1)));
    
    assertThat(jsonOf("{\"a\":true}"), jsonProperty("b", jsonUndefined()));
    assertThat(jsonOf("{\"a\":null}"), jsonProperty("a", jsonNull()));
  }
  
  @Test
  public void jsonPropertyFailure() {
    assertThat(() -> assertThat(jsonOf(null), jsonProperty("a", true)),
        shouldFailWithMessage(containsString("no value")));

    assertThat(() -> assertThat(jsonOf("[]"), jsonProperty("a", true)),
        shouldFailWithMessage(containsString("not a JSON object")));

    assertThat(() -> assertThat(jsonOf("{\"a\":1}"), jsonProperty("b", 1)),
        shouldFailWithMessage(Matchers.allOf(containsString("Expected: property \"b\""), containsString("no value"))));

    assertThat(() -> assertThat(jsonOf("{\"a\":1}"), jsonProperty("a", 2)),
        shouldFailWithMessage(Matchers.allOf(containsString("Expected: property \"a\""), containsString("actual: 1"))));
}
  
  @SuppressWarnings("unchecked")
  @Test
  public void isJsonArraySuccess() {
    assertThat(jsonOf("[]"), isJsonArray(emptyIterable()));
    assertThat(jsonOf("[true]"), isJsonArray(contains(jsonEqualsValue(true))));
    assertThat(jsonOf("[true, false]"), isJsonArray(contains(jsonEqualsValue(true), jsonEqualsValue(false))));
  }
  
  @Test
  public void isJsonArrayFailure() {
    assertThat(() -> assertThat(jsonOf(null), isJsonArray(emptyIterable())),
        shouldFailWithMessage(containsString("no value")));

    assertThat(() -> assertThat(jsonOf("{}"), isJsonArray(emptyIterable())),
        shouldFailWithMessage(containsString("not a JSON array")));
       
    assertThat(() -> assertThat(jsonOf("[true]"), isJsonArray(contains(jsonEqualsValue(false)))),
        shouldFailWithMessage(containsString("item 0: expected: false\nactual: true")));
  }
  
  private static Matcher<Runnable> shouldFailWithMessage(Matcher<String> matcher) {
    return new TypeSafeDiagnosingMatcher<Runnable>() {
      @Override
      public void describeTo(Description description) {
        description.appendText("should fail with message:");
        matcher.describeTo(description);
      }

      @Override
      protected boolean matchesSafely(Runnable item, Description mismatchDescription) {
        try {
          item.run();
          mismatchDescription.appendText("did not throw exception");
          return false;
        } catch (AssertionError e) {
          String message = e.getMessage().trim();
          if (!matcher.matches(message)) {
            matcher.describeMismatch(message, mismatchDescription);
            return false;
          }
          return true;
        }
      }
    };
  }
}
