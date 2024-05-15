package com.launchdarkly.sdk;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.launchdarkly.sdk.TestHelpers.listFromIterable;
import static java.util.Arrays.asList;
import static java.util.Collections.addAll;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@SuppressWarnings("javadoc")
public class LDValueArrayTest {
  private static final LDValue anArrayValue = LDValue.buildArray().add(LDValue.of(3)).build();

  @Test
  public void canGetSizeOfArray() {
    assertEquals(1, anArrayValue.size());
  }
  
  @Test
  public void arrayCanGetItemByIndex() {
    assertEquals(LDValueType.ARRAY, anArrayValue.getType());
    assertEquals(LDValue.of(3), anArrayValue.get(0));
    assertEquals(LDValue.ofNull(), anArrayValue.get(-1));
    assertEquals(LDValue.ofNull(), anArrayValue.get(1));
  }
  
  @Test
  public void arrayCanBeEnumerated() {
    LDValue a = LDValue.of("a");
    LDValue b = LDValue.of("b");
    List<LDValue> values = new ArrayList<>();
    for (LDValue v: LDValue.buildArray().add(a).add(b).build().values()) {
      values.add(v);
    }
    List<LDValue> expected = new ArrayList<>();
    addAll(expected, a, b);
    assertEquals(expected, values);
  }
  
  @Test
  public void arrayBuilderOverloadsForPrimitiveTypes() {
    LDValue a = LDValue.buildArray()
        .add(true)
        .add(1)
        .add(2L)
        .add(3.5f)
        .add(4.5d)
        .add("x")
        .build();
    LDValue expected = LDValue.buildArray()
        .add(LDValue.of(true))
        .add(LDValue.of(1))
        .add(LDValue.of(2L))
        .add(LDValue.of(3.5f))
        .add(LDValue.of(4.5d))
        .add(LDValue.of("x"))
        .build();
    assertEquals(expected, a);
  }
  
  @Test
  public void arrayBuilderCanAddValuesAfterBuilding() {
    ArrayBuilder builder = LDValue.buildArray();
    builder.add("a");
    LDValue firstArray = builder.build();
    assertEquals(1, firstArray.size());
    builder.add("b");
    LDValue secondArray = builder.build();
    assertEquals(2, secondArray.size());
    assertEquals(1, firstArray.size());
  }
  
  @Test
  public void primitiveValuesBehaveLikeEmptyArray() {
    LDValue[] values = new LDValue[] {
        LDValue.ofNull(),
        LDValue.of(true),
        LDValue.of(1),
        LDValue.of(1L),
        LDValue.of(1.0f),
        LDValue.of(1.0d),
        LDValue.of("x")
    };
    for (LDValue value: values) {
      assertEquals(value.toString(), 0, value.size());
      assertEquals(value.toString(), LDValue.of(null), value.get(-1));
      assertEquals(value.toString(), LDValue.of(null), value.get(0));
      assertThat(value.values(), Matchers.emptyIterable());
    }
  }

  @Test
  public void nullsInArrayAreAlwaysNullValueInstancesNotJavaNulls() {
    LDValue a1 = LDValue.buildArray().add((LDValue)null).build();
    assertEquals(1, a1.size());
    assertNotNull(a1.get(0));
    assertEquals(LDValue.ofNull(), a1.get(0));
    
    LDValue a2 = LDValue.parse("[null]");
    assertEquals(1, a2.size());
    assertNotNull(a2.get(0));
    assertEquals(LDValue.ofNull(), a2.get(0));
  }
  
  @Test
  public void equalValuesAreEqual() {
    List<List<LDValue>> testValues = asList(
        asList(LDValue.buildArray().build(), LDValue.buildArray().build()),
        asList(LDValue.buildArray().add("a").build(), LDValue.buildArray().add("a").build()),
        asList(LDValue.buildArray().add("a").add("b").build(),
            LDValue.buildArray().add("a").add("b").build()),
        asList(LDValue.buildArray().add("a").add("c").build(),
            LDValue.buildArray().add("a").add("c").build()),
        asList(LDValue.buildArray().add("a").add(LDValue.buildArray().add("b").add("c").build()).build(),
            LDValue.buildArray().add("a").add(LDValue.buildArray().add("b").add("c").build()).build()),
        asList(LDValue.buildArray().add("a").add(LDValue.buildArray().add("b").add("d").build()).build(),
            LDValue.buildArray().add("a").add(LDValue.buildArray().add("b").add("d").build()).build())
        );
    TestHelpers.doEqualityTests(testValues);
  }
  
  @Test
  public void arrayOf() {
    assertEquals(LDValue.buildArray().add(LDValue.of(2)).add(LDValue.of("three")).build(),
          LDValue.arrayOf(LDValue.of(2), LDValue.of("three")));

    assertEquals(LDValue.buildArray().build(), LDValue.arrayOf());

    assertEquals(LDValue.buildArray().build(), LDValue.arrayOf((LDValue[])null));
  }
  
  @Test
  public void testTypeConversions() {
    testTypeConversion(LDValue.Convert.Boolean, new Boolean[] { true, false }, false, LDValue.of(true), LDValue.of(false));
    testTypeConversion(LDValue.Convert.Integer, new Integer[] { 1, 2 }, 0, LDValue.of(1), LDValue.of(2));
    testTypeConversion(LDValue.Convert.Long, new Long[] { 1L, 2L }, 0L, LDValue.of(1L), LDValue.of(2L));
    testTypeConversion(LDValue.Convert.Float, new Float[] { 1.5f, 2.5f }, 0f, LDValue.of(1.5f), LDValue.of(2.5f));
    testTypeConversion(LDValue.Convert.Double, new Double[] { 1.5d, 2.5d }, 0d, LDValue.of(1.5d), LDValue.of(2.5d));
    testTypeConversion(LDValue.Convert.String, new String[] { "a", "b" }, null, LDValue.of("a"), LDValue.of("b"));
  }
  
  private <T> void testTypeConversion(LDValue.Converter<T> converter, T[] values, T valueForNull, LDValue... ldValues) {
    ArrayBuilder ab = LDValue.buildArray();
    for (LDValue v: ldValues) {
      ab.add(v);
    }
    ab.add(LDValue.ofNull()); // all the types we're testing are by definition nullable
    LDValue arrayValue = ab.build();
    
    T[] allValues = Arrays.copyOf(values, values.length + 1);
    allValues[values.length] = null;
    assertEquals(arrayValue, converter.arrayOf(allValues));
    
    List<T> listWithActualNull = new ArrayList<>();
    List<T> listWithDefaultValueForNull = new ArrayList<>();
    for (T v: values) {
      listWithActualNull.add(v);
      listWithDefaultValueForNull.add(v);
    }
    listWithActualNull.add(null);
    listWithDefaultValueForNull.add(valueForNull); // see doc comment for LDValue.valuesAs()
    assertEquals(arrayValue, converter.arrayFrom(listWithActualNull));
    assertEquals(listWithDefaultValueForNull, listFromIterable(arrayValue.valuesAs(converter)));
  }
}
