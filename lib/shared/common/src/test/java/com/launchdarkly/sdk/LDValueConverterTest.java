package com.launchdarkly.sdk;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

@SuppressWarnings("javadoc")
public class LDValueConverterTest extends BaseTest {
  @Test
  public void nullAndJsonNullConvertToNull() {
    assertThat(LDValueConverter.toJavaObject(null), is(nullValue()));
    assertThat(LDValueConverter.toJavaObject(LDValue.ofNull()), is(nullValue()));
  }

  @Test
  public void integralNumberBecomesLong() {
    Object converted = LDValueConverter.toJavaObject(LDValue.of(5));
    assertThat(converted, instanceOf(Long.class));
    assertThat(converted, is((Object) 5L));
  }

  @Test
  public void fractionalNumberBecomesDouble() {
    Object converted = LDValueConverter.toJavaObject(LDValue.of(5.5));
    assertThat(converted, instanceOf(Double.class));
    assertThat(converted, is((Object) 5.5));
  }

  @Test
  public void integerBoundaryExactly2Pow53BecomesLong() {
    double boundary = 9007199254740992.0; // 2^53
    Object converted = LDValueConverter.toJavaObject(LDValue.of(boundary));
    assertThat(converted, instanceOf(Long.class));
    assertThat(converted, is((Object) (long) boundary));
  }

  @Test
  public void integerJustOutsideBoundaryBecomesDouble() {
    // 2^53 + 1 cannot be represented exactly as a double, so the double value is 2^53 + 2.
    // The key point is that the result is a Double, not a Long.
    double beyondBoundary = 9007199254740994.0; // clearly > 2^53
    Object converted = LDValueConverter.toJavaObject(LDValue.of(beyondBoundary));
    assertThat(converted, instanceOf(Double.class));
  }

  @Test
  public void nanBecomesDouble() {
    Object converted = LDValueConverter.toJavaObject(LDValue.of(Double.NaN));
    assertThat(converted, instanceOf(Double.class));
  }

  @Test
  public void infinityBecomesDouble() {
    Object posInf = LDValueConverter.toJavaObject(LDValue.of(Double.POSITIVE_INFINITY));
    assertThat(posInf, instanceOf(Double.class));
    Object negInf = LDValueConverter.toJavaObject(LDValue.of(Double.NEGATIVE_INFINITY));
    assertThat(negInf, instanceOf(Double.class));
  }

  @Test
  public void stringConvertsDirectly() {
    assertThat(LDValueConverter.toJavaObject(LDValue.of("hi")), is((Object) "hi"));
  }

  @Test
  public void booleanConvertsDirectly() {
    assertThat(LDValueConverter.toJavaObject(LDValue.of(true)), is((Object) Boolean.TRUE));
    assertThat(LDValueConverter.toJavaObject(LDValue.of(false)), is((Object) Boolean.FALSE));
  }

  @Test
  public void nestedObjectAndArrayConvert() {
    LDValue value = LDValue.parse("{\"a\":1,\"b\":[\"x\",2],\"c\":{\"d\":true}}");
    Map<String, Object> map = LDValueConverter.toMap(value);
    assertThat(map.get("a"), is((Object) 1L));
    assertThat(((List<?>) map.get("b")).get(0), is((Object) "x"));
    assertThat(((List<?>) map.get("b")).get(1), is((Object) 2L));
    assertThat(((Map<?, ?>) map.get("c")).get("d"), is((Object) Boolean.TRUE));
  }

  @Test
  public void fieldOrderMatchesInputKeyIteration() {
    // LDValueConverter uses LinkedHashMap so output key order equals the order value.keys() iterates.
    LDValue value = LDValue.buildObject().put("z", 1).put("a", 2).put("m", 3).build();
    Map<String, Object> map = LDValueConverter.toMap(value);
    List<String> expectedKeys = new ArrayList<>();
    for (String key : value.keys()) {
      expectedKeys.add(key);
    }
    List<String> actualKeys = new ArrayList<>(map.keySet());
    assertThat(actualKeys, is(expectedKeys));
  }

  @Test
  public void toMapReturnsNullForNonObject() {
    assertThat(LDValueConverter.toMap(null), is(nullValue()));
    assertThat(LDValueConverter.toMap(LDValue.ofNull()), is(nullValue()));
    assertThat(LDValueConverter.toMap(LDValue.of("not-an-object")), is(nullValue()));
    assertThat(LDValueConverter.toMap(LDValue.parse("[1,2,3]")), is(nullValue()));
    assertThat(LDValueConverter.toMap(LDValue.of(42)), is(nullValue()));
  }

  @Test
  public void mapResultIsUnmodifiable() {
    LDValue value = LDValue.parse("{\"a\":1}");
    Map<String, Object> map = LDValueConverter.toMap(value);
    try {
      map.put("x", "y");
      throw new AssertionError("Expected UnsupportedOperationException");
    } catch (UnsupportedOperationException e) {
      // expected
    }
  }

  @Test
  public void listResultIsUnmodifiable() {
    LDValue value = LDValue.parse("[1,2,3]");
    Object result = LDValueConverter.toJavaObject(value);
    assertThat(result, instanceOf(List.class));
    @SuppressWarnings("unchecked")
    List<Object> list = (List<Object>) result;
    try {
      list.add("x");
      throw new AssertionError("Expected UnsupportedOperationException");
    } catch (UnsupportedOperationException e) {
      // expected
    }
  }

  @Test
  public void deeplyNestedInputDoesNotOverflowAndIsCapped() {
    int depth = LDValueConverter.MAX_DEPTH + 50;
    StringBuilder json = new StringBuilder();
    for (int i = 0; i < depth; i++) {
      json.append('[');
    }
    json.append("1");
    for (int i = 0; i < depth; i++) {
      json.append(']');
    }
    // Should neither throw nor StackOverflow; the top level is still a List.
    Object converted = LDValueConverter.toJavaObject(LDValue.parse(json.toString()));
    assertThat(converted, instanceOf(List.class));
    // Walk down to MAX_DEPTH (still within cap at MAX_DEPTH-1), verify it holds a list.
    Object current = converted;
    for (int i = 0; i < LDValueConverter.MAX_DEPTH; i++) {
      assertThat(current, instanceOf(List.class));
      current = ((List<?>) current).get(0);
    }
    // After descending MAX_DEPTH times, the value is dropped and becomes null.
    assertThat(current, is(nullValue()));
  }

  @Test
  public void largeIntegerConvertsToLong() {
    Object converted = LDValueConverter.toJavaObject(LDValue.of(100));
    assertThat(converted, instanceOf(Long.class));
    assertThat(converted, is((Object) 100L));
  }

  @Test
  public void negativeIntegerBoundaryBecomesLong() {
    double boundary = -9007199254740992.0; // -2^53
    Object converted = LDValueConverter.toJavaObject(LDValue.of(boundary));
    assertThat(converted, instanceOf(Long.class));
  }
}
