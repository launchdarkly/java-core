package com.launchdarkly.testhelpers;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import org.junit.Test;

import static com.launchdarkly.testhelpers.AssertionsTest.requireAssertionError;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@SuppressWarnings("javadoc")
public class JsonTestValueTest {
  @Test
  public void parseUndefined() {
    JsonTestValue v = JsonTestValue.jsonOf(null);
    assertThat(v, notNullValue());
    assertThat(v.isDefined(), equalTo(false));
    assertThat(v.raw, nullValue());
    assertThat(v.parsed, nullValue());
  }
  
  @Test
  public void parseMalformed() {
    assertThat(requireAssertionError(() -> JsonTestValue.jsonOf("{no")),
        allOf(containsString("not valid JSON"), containsString("{no")));
  }
  
  @Test
  public void parseSuccess() {
    JsonTestValue v = JsonTestValue.jsonOf("123");
    assertThat(v, notNullValue());
    assertThat(v.isDefined(), equalTo(true));
    assertThat(v.raw, equalTo("123"));
    assertThat(v.parsed, equalTo(new JsonPrimitive(123)));
  }
  
  @Test
  public void fromParsedUndefined() {
    JsonTestValue v = JsonTestValue.ofParsed(null);
    assertThat(v, notNullValue());
    assertThat(v.isDefined(), equalTo(false));
    assertThat(v.raw, nullValue());
    assertThat(v.parsed, nullValue());
  }
  
  @Test
  public void fromParsedValue() {
    JsonElement parsed = new JsonPrimitive(123);
    JsonTestValue v = JsonTestValue.ofParsed(parsed);
    assertThat(v, notNullValue());
    assertThat(v.isDefined(), equalTo(true));
    assertThat(v.raw, equalTo("123"));
    assertThat(v.parsed, equalTo(parsed));
  }
  
  @Test
  public void fromValue() {
    JsonTestValue v = JsonTestValue.jsonFromValue(true);
    assertThat(v, notNullValue());
    assertThat(v.isDefined(), equalTo(true));
    assertThat(v.raw, equalTo("true"));
    assertThat(v.parsed, equalTo(new JsonPrimitive(true)));
  }
}
