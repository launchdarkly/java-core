package com.launchdarkly.sdk.server.ai.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import com.launchdarkly.sdk.LDValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

@SuppressWarnings("javadoc")
public class LDValueConverterTest {
  @Test
  public void nullAndJsonNullConvertToNull() {
    assertThat(LDValueConverter.toJavaObject(null), is(nullValue()));
    assertThat(LDValueConverter.toJavaObject(LDValue.ofNull()), is(nullValue()));
  }

  @Test
  public void integralNumberBecomesLong() {
    Object converted = LDValueConverter.toJavaObject(LDValue.of(100));
    assertThat(converted, instanceOf(Long.class));
    assertThat(converted, is((Object) 100L));
  }

  @Test
  public void fractionalNumberBecomesDouble() {
    Object converted = LDValueConverter.toJavaObject(LDValue.of(0.75));
    assertThat(converted, instanceOf(Double.class));
    assertThat(converted, is((Object) 0.75));
  }

  @Test
  public void stringAndBooleanConvertDirectly() {
    assertThat(LDValueConverter.toJavaObject(LDValue.of("hi")), is((Object) "hi"));
    assertThat(LDValueConverter.toJavaObject(LDValue.of(true)), is((Object) Boolean.TRUE));
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
  public void toMapReturnsNullForNonObject() {
    assertThat(LDValueConverter.toMap(LDValue.of("not-an-object")), is(nullValue()));
    assertThat(LDValueConverter.toMap(LDValue.parse("[1,2,3]")), is(nullValue()));
  }

  @Test
  public void deeplyNestedInputDoesNotOverflowAndIsCapped() {
    int depth = LDValueConverter.MAX_DEPTH + 50;
    StringBuilder json = new StringBuilder();
    for (int i = 0; i < depth; i++) {
      json.append('[');
    }
    for (int i = 0; i < depth; i++) {
      json.append(']');
    }
    // Should neither throw nor StackOverflow; the top level is still a List.
    Object converted = LDValueConverter.toJavaObject(LDValue.parse(json.toString()));
    assertThat(converted, instanceOf(List.class));
  }

  @Test
  public void fromJavaObjectConvertsScalars() {
    assertThat(LDValueConverter.fromJavaObject(null), is(LDValue.ofNull()));
    assertThat(LDValueConverter.fromJavaObject("hi"), is(LDValue.of("hi")));
    assertThat(LDValueConverter.fromJavaObject(Boolean.TRUE), is(LDValue.of(true)));
    assertThat(LDValueConverter.fromJavaObject(7), is(LDValue.of(7)));
    assertThat(LDValueConverter.fromJavaObject(7L), is(LDValue.of(7L)));
    assertThat(LDValueConverter.fromJavaObject(0.5), is(LDValue.of(0.5)));
  }

  @Test
  public void fromJavaObjectConvertsNestedMapsAndLists() {
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("a", 1L);
    input.put("b", Arrays.asList("x", 2L));
    Map<String, Object> nested = new LinkedHashMap<>();
    nested.put("d", true);
    input.put("c", nested);

    LDValue value = LDValueConverter.fromJavaObject(input);
    assertThat(value, is(LDValue.parse("{\"a\":1,\"b\":[\"x\",2],\"c\":{\"d\":true}}")));
  }

  @Test
  public void fromJavaObjectRoundTrips() {
    LDValue original = LDValue.parse("{\"name\":\"gpt-4\",\"n\":3,\"f\":0.25,\"on\":true,\"list\":[1,2]}");
    Object asJava = LDValueConverter.toJavaObject(original);
    assertThat(LDValueConverter.fromJavaObject(asJava), is(original));
  }

  @Test
  public void fromJavaObjectDropsUnsupportedTypes() {
    // An unsupported value type becomes JSON null rather than throwing.
    assertThat(LDValueConverter.fromJavaObject(new Object()), is(LDValue.ofNull()));
    List<Object> list = new ArrayList<>();
    list.add(new Object());
    assertThat(LDValueConverter.fromJavaObject(list), is(LDValue.parse("[null]")));
  }
}
