package com.launchdarkly.sdk;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.MalformedJsonException;
import com.launchdarkly.sdk.json.SerializationException;

import org.junit.Test;

import java.io.StringReader;
import java.util.List;

import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("javadoc")
public class LDValueTest extends BaseTest {
  private static final int someInt = 3;
  private static final long someLong = 3;
  private static final float someFloat = 3.25f;
  private static final double someDouble = 3.25d;
  private static final String someString = "hi";
  
  private static final LDValue aTrueBoolValue = LDValue.of(true);
  private static final LDValue anIntValue = LDValue.of(someInt);
  private static final LDValue aLongValue = LDValue.of(someLong);
  private static final LDValue aFloatValue = LDValue.of(someFloat);
  private static final LDValue aDoubleValue = LDValue.of(someDouble);
  private static final LDValue aStringValue = LDValue.of(someString);
  private static final LDValue aNumericLookingStringValue = LDValue.of("3");
  private static final LDValue anArrayValue = LDValue.buildArray().add(LDValue.of(3)).build();
  private static final LDValue anObjectValue = LDValue.buildObject().put("1", LDValue.of("x")).build();
  
  @Test
  public void normalize() {
    assertEquals(LDValue.ofNull(), LDValue.normalize(null));
    assertEquals(LDValue.ofNull(), LDValue.normalize(LDValue.ofNull()));
    assertEquals(LDValue.of(true), LDValue.normalize(LDValue.of(true)));
  }
  
  @Test
  public void isNull() {
    assertTrue(LDValue.ofNull().isNull());
    LDValue[] nonNulls = new LDValue[] { aStringValue, anIntValue, aLongValue, aFloatValue,
        aDoubleValue, anArrayValue, anObjectValue };
    for (LDValue value: nonNulls) {
      assertFalse(value.toString(), value.isNull());
    }
  }
  
  @Test
  public void isNumber() {
    LDValue[] nonNumerics = new LDValue[] { LDValue.ofNull(), aStringValue, anArrayValue, anObjectValue };
    LDValue[] numerics = new LDValue[] { anIntValue, aLongValue, aFloatValue, aDoubleValue };
    for (LDValue value: nonNumerics) {
      assertFalse(value.toString(), value.isNumber());
    }
    for (LDValue value: numerics) {
      assertTrue(value.toString(), value.isNumber());
    }
  }

  @Test
  public void isInt() {
    LDValue[] nonInts = new LDValue[] { LDValue.ofNull(), aStringValue, anArrayValue, anObjectValue,
        LDValue.of(1.5f), LDValue.of(1.5d) };
    LDValue[] ints = new LDValue[] { anIntValue, aLongValue, LDValue.of(1.0f), LDValue.of(1.0d) };
    for (LDValue value: nonInts) {
      assertFalse(value.toString(), value.isInt());
    }
    for (LDValue value: ints) {
      assertTrue(value.toString(), value.isInt());
    }
  }

  @Test
  public void isString() {
    LDValue[] nonStrings = new LDValue[] { anIntValue, aLongValue, aFloatValue,
        aDoubleValue, anArrayValue, anObjectValue };
    assertTrue(aStringValue.isString());
    for (LDValue value: nonStrings) {
      assertFalse(value.toString(), value.isString());
    }
  }

  @Test
  public void canGetValueAsBoolean() {
    assertEquals(LDValueType.BOOLEAN, aTrueBoolValue.getType());
    assertTrue(aTrueBoolValue.booleanValue());
  }
  
  @Test
  public void nonBooleanValueAsBooleanIsFalse() {
    LDValue[] values = new LDValue[] {
        LDValue.ofNull(),
        aStringValue,
        anIntValue,
        aLongValue,
        aFloatValue,
        aDoubleValue,
        anArrayValue,
        anObjectValue,
    };
    for (LDValue value: values) {
      String desc = value.toString();
      assertNotEquals(desc, LDValueType.BOOLEAN, value.getType());
      assertFalse(desc, value.booleanValue());
    }
  }
  
  @Test
  public void canGetValueAsString() {
    assertEquals(LDValueType.STRING, aStringValue.getType());
    assertEquals(someString, aStringValue.stringValue());
  }

  @Test
  public void nonStringValueAsStringIsNull() {
    LDValue[] values = new LDValue[] {
        LDValue.ofNull(),
        aTrueBoolValue,
        anIntValue,
        aLongValue,
        aFloatValue,
        aDoubleValue,
        anArrayValue,
        anObjectValue
    };
    for (LDValue value: values) {
      String desc = value.toString();
      assertNotEquals(desc, LDValueType.STRING, value.getType());
      assertNull(desc, value.stringValue());
    }
  }
  
  @Test
  public void nullStringConstructorGivesNullInstance() {
    assertEquals(LDValue.ofNull(), LDValue.of((String)null));
  }
  
  @Test
  public void canGetIntegerValueOfAnyNumericType() {
    LDValue[] values = new LDValue[] {
        LDValue.of(3),
        LDValue.of(3L),
        LDValue.of(3.0f),
        LDValue.of(3.25f),
        LDValue.of(3.75f),
        LDValue.of(3.0d),
        LDValue.of(3.25d),
        LDValue.of(3.75d)
    };
    for (LDValue value: values) {
      String desc = value.toString();
      assertEquals(desc, LDValueType.NUMBER, value.getType());
      assertEquals(desc, 3, value.intValue());
      assertEquals(desc, 3L, value.longValue());
    }
  }
  
  @Test
  public void canGetFloatValueOfAnyNumericType() {
    LDValue[] values = new LDValue[] {
        LDValue.of(3),
        LDValue.of(3L),
        LDValue.of(3.0f),
        LDValue.of(3.0d),
    };
    for (LDValue value: values) {
      String desc = value.toString();
      assertEquals(desc, LDValueType.NUMBER, value.getType());
      assertEquals(desc, 3.0f, value.floatValue(), 0);
    }
  }
  
  @Test
  public void canGetDoubleValueOfAnyNumericType() {
    LDValue[] values = new LDValue[] {
        LDValue.of(3),
        LDValue.of(3L),
        LDValue.of(3.0f),
        LDValue.of(3.0d),
    };
    for (LDValue value: values) {
      String desc = value.toString();
      assertEquals(desc, LDValueType.NUMBER, value.getType());
      assertEquals(desc, 3.0d, value.doubleValue(), 0);
    }
  }

  @Test
  public void nonNumericValueAsNumberIsZero() {
    LDValue[] values = new LDValue[] {
        LDValue.ofNull(),
        aTrueBoolValue,
        aStringValue,
        aNumericLookingStringValue,
        anArrayValue,
        anObjectValue
    };
    for (LDValue value: values) {
      String desc = value.toString();
      assertNotEquals(desc, LDValueType.NUMBER, value.getType());
      assertEquals(desc, 0, value.intValue());
      assertEquals(desc, 0, value.longValue());
      assertEquals(desc, 0f, value.floatValue(), 0);
      assertEquals(desc, 0d, value.doubleValue(), 0);
    }
  }
  
  @Test
  public void equalValuesAreEqual() {
    List<List<LDValue>> testValues = asList(
        asList(LDValue.ofNull(), LDValue.ofNull()),
        asList(LDValue.of(true), LDValue.of(true)),
        asList(LDValue.of(false), LDValue.of(false)),
        asList(LDValue.of(1), LDValue.of(1)),
        asList(LDValue.of(2), LDValue.of(2)),
        asList(LDValue.of(3), LDValue.of(3.0f)),
        asList(LDValue.of("a"), LDValue.of("a")),
        asList(LDValue.of("b"), LDValue.of("b"))
        );
    TestHelpers.doEqualityTests(testValues);
  }
  
  @Test
  public void commonValuesAreInterned() {
    assertSame(LDValue.of(true), LDValue.of(true));
    assertSame(LDValue.of(false), LDValue.of(false));
    assertSame(LDValue.of(0), LDValue.of(0));
    assertSame(LDValue.of(""), LDValue.of(""));
  }
  
  @Test
  public void canUseLongTypeForNumberGreaterThanMaxInt() {
    long n = (long)Integer.MAX_VALUE + 1;
    assertEquals(n, LDValue.of(n).longValue());
    assertEquals(n, LDValue.Convert.Long.toType(LDValue.of(n)).longValue());
    assertEquals(n, LDValue.Convert.Long.fromType(n).longValue());
  }

  @Test
  public void canUseDoubleTypeForNumberGreaterThanMaxFloat() {
    double n = (double)Float.MAX_VALUE + 1;
    assertEquals(n, LDValue.of(n).doubleValue(), 0);
    assertEquals(n, LDValue.Convert.Double.toType(LDValue.of(n)).doubleValue(), 0);
    assertEquals(n, LDValue.Convert.Double.fromType(n).doubleValue(), 0);
  }
  
  @Test
  public void parseThrowsRuntimeExceptionForMalformedJson() {
    try {
      LDValue.parse("{");
    } catch (RuntimeException e) {
      assertThat(e.getCause(), instanceOf(SerializationException.class));
    }
  }

  @Test
  public void testLowLevelTypeAdapter() throws Exception {
    // This test ensures full test coverage of LDValueTypeAdapter code paths that might not
    // be exercised indirectly by other tests.
    verifyTypeAdapterRead("null", LDValue.ofNull());
    verifyTypeAdapterRead("true", LDValue.of(true));
    verifyTypeAdapterRead("1", LDValue.of(1));
    verifyTypeAdapterRead("\"x\"", LDValue.of("x"));
    verifyTypeAdapterRead("[1,2]", LDValue.buildArray().add(1).add(2).build());
    verifyTypeAdapterRead("{\"a\":1}", LDValue.buildObject().put("a", 1).build());

    try (JsonReader r = new JsonReader(new StringReader("]"))) {
      try {
        LDValueTypeAdapter.INSTANCE.read(r);
      } catch (MalformedJsonException e) {}
    }
  }

  private static void verifyTypeAdapterRead(String jsonString, LDValue expectedValue) throws Exception {
    try (JsonReader r = new JsonReader(new StringReader(jsonString))) {
      assertEquals(expectedValue, LDValueTypeAdapter.INSTANCE.read(r));
    }
  }
}
