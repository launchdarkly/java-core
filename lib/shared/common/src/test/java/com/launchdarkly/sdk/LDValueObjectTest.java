package com.launchdarkly.sdk;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;
import static java.util.Collections.addAll;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyIterable;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@SuppressWarnings("javadoc")
public class LDValueObjectTest {
  private static final LDValue anObjectValue = LDValue.buildObject().put("1", LDValue.of("x")).build();

  @Test
  public void canGetSizeOfObject() {
    assertEquals(1, anObjectValue.size());
  }
  
  @Test
  public void objectCanGetValueByName() {
    assertEquals(LDValueType.OBJECT, anObjectValue.getType());
    assertEquals(LDValue.of("x"), anObjectValue.get("1"));
    assertEquals(LDValue.ofNull(), anObjectValue.get(null));
    assertEquals(LDValue.ofNull(), anObjectValue.get("2"));
  }
  
  @Test
  public void objectKeysCanBeEnumerated() {
    List<String> keys = new ArrayList<>();
    for (String key: LDValue.buildObject().put("1", LDValue.of("x")).put("2", LDValue.of("y")).build().keys()) {
      keys.add(key);
    }
    Collections.sort(keys);
    List<String> expected = new ArrayList<>();
    addAll(expected, "1", "2");
    assertEquals(expected, keys);
  }

  @Test
  public void objectValuesCanBeEnumerated() {
    List<String> values = new ArrayList<>();
    for (LDValue value: LDValue.buildObject().put("1", LDValue.of("x")).put("2", LDValue.of("y")).build().values()) {
      values.add(value.stringValue());
    }
    Collections.sort(values);
    List<String> expected = new ArrayList<>();
    addAll(expected, "x", "y");
    assertEquals(expected, values);
  }

  @Test
  public void objectBuilderOverloadsForPrimitiveTypes() {
    LDValue a = LDValue.buildObject()
        .put("a", true)
        .put("b", 1)
        .put("c", 2L)
        .put("d", 3.5f)
        .put("e", 4.5d)
        .put("f", "x")
        .build();
    LDValue expected = LDValue.buildObject()
        .put("a", LDValue.of(true))
        .put("b", LDValue.of(1))
        .put("c", LDValue.of(2L))
        .put("d", LDValue.of(3.5f))
        .put("e", LDValue.of(4.5d))
        .put("f", LDValue.of("x"))
        .build();
    assertEquals(expected, a);
  }
  
  @Test
  public void objectBuilderCanAddValuesAfterBuilding() {
    ObjectBuilder builder = LDValue.buildObject();
    builder.put("a", 1);
    LDValue firstObject = builder.build();
    assertEquals(1, firstObject.size());
    builder.put("b", 2);
    LDValue secondObject = builder.build();
    assertEquals(2, secondObject.size());
    assertEquals(1, firstObject.size());
  }
  
  @Test
  public void primitiveValuesBehaveLikeEmptyObject() {
    LDValue[] values = new LDValue[] {
        LDValue.ofNull(),
        LDValue.ofNull(),
        LDValue.of(true),
        LDValue.of(1),
        LDValue.of(1L),
        LDValue.of(1.0f),
        LDValue.of(1.0d),
        LDValue.of("x")
    };
    for (LDValue value: values) {
      assertEquals(value.toString(), LDValue.of(null), value.get(null));
      assertEquals(value.toString(), LDValue.of(null), value.get("1"));
      assertThat(value.keys(), emptyIterable());
    }
  }

  @Test
  public void nullsInObjectAreAlwaysNullValueInstancesNotJavaNulls() {
    LDValue o1 = LDValue.buildObject().put("x", (LDValue)null).build();
    assertEquals(1, o1.size());
    assertNotNull(o1.get("x"));
    assertEquals(LDValue.ofNull(), o1.get("x"));
    
    LDValue o2 = LDValue.parse("{\"x\":null}");
    assertEquals(1, o2.size());
    assertNotNull(o2.get("x"));
    assertEquals(LDValue.ofNull(), o2.get("x"));
  }
  
  @Test
  public void equalValuesAreEqual()
  {
    List<List<LDValue>> testValues = asList(
        asList(LDValue.buildObject().build(), LDValue.buildObject().build()),
        asList(LDValue.buildObject().put("a", LDValue.of(1)).build(),
            LDValue.buildObject().put("a", LDValue.of(1)).build()),
        asList(LDValue.buildObject().put("a", LDValue.of(2)).build(),
            LDValue.buildObject().put("a", LDValue.of(2)).build()),
        asList(LDValue.buildObject().put("a", LDValue.of(1)).put("b", LDValue.of(2)).build(),
            LDValue.buildObject().put("b", LDValue.of(2)).put("a", LDValue.of(1)).build())
        );
    TestHelpers.doEqualityTests(testValues);
  }
  
  @Test
  public void testTypeConversions() {
    testTypeConversion(LDValue.Convert.Boolean, new Boolean[] { true, false }, LDValue.of(true), LDValue.of(false));
    testTypeConversion(LDValue.Convert.Integer, new Integer[] { 1, 2 }, LDValue.of(1), LDValue.of(2));
    testTypeConversion(LDValue.Convert.Long, new Long[] { 1L, 2L }, LDValue.of(1L), LDValue.of(2L));
    testTypeConversion(LDValue.Convert.Float, new Float[] { 1.5f, 2.5f }, LDValue.of(1.5f), LDValue.of(2.5f));
    testTypeConversion(LDValue.Convert.Double, new Double[] { 1.5d, 2.5d }, LDValue.of(1.5d), LDValue.of(2.5d));
    testTypeConversion(LDValue.Convert.String, new String[] { "a", "b" }, LDValue.of("a"), LDValue.of("b"));
  }
  
  private <T> void testTypeConversion(LDValue.Converter<T> converter, T[] values, LDValue... ldValues) {
    ObjectBuilder ob = LDValue.buildObject();
    int i = 0;
    for (LDValue v: ldValues) {
      ob.put(String.valueOf(++i), v);
    }
    LDValue objectValue = ob.build();
    Map<String, T> map = new HashMap<>();
    i = 0;
    for (T v: values) {
      map.put(String.valueOf(++i), v);
    }
    assertEquals(objectValue, converter.objectFrom(map));
  }
}
