package com.launchdarkly.sdk;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static com.launchdarkly.sdk.EvaluationDetail.NO_VARIATION;
import static com.launchdarkly.sdk.EvaluationReason.ErrorKind.CLIENT_NOT_READY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("javadoc")
public class EvaluationDetailTest extends BaseTest {
  @Test
  public void getValue() {
    assertEquals("x", EvaluationDetail.fromValue("x", 0, EvaluationReason.off()).getValue());
    assertEquals(LDValue.of("x"), EvaluationDetail.error(CLIENT_NOT_READY, LDValue.of("x")).getValue());
  }
  
  @Test
  public void getVariationIndex() {
    assertEquals(1, EvaluationDetail.fromValue("x", 1, EvaluationReason.off()).getVariationIndex());
    assertEquals(NO_VARIATION, EvaluationDetail.fromValue("x", NO_VARIATION, EvaluationReason.off()).getVariationIndex());
    assertEquals(NO_VARIATION, EvaluationDetail.fromValue("x", -2, EvaluationReason.off()).getVariationIndex());
    assertEquals(NO_VARIATION, EvaluationDetail.error(CLIENT_NOT_READY, LDValue.of("x")).getVariationIndex());
  }
  
  @Test
  public void getReason() {
    assertEquals(EvaluationReason.fallthrough(), EvaluationDetail.fromValue("x", 1, EvaluationReason.fallthrough()).getReason());
    assertEquals(EvaluationReason.error(CLIENT_NOT_READY),
        EvaluationDetail.error(CLIENT_NOT_READY, LDValue.of("x")).getReason());
  }
  
  @Test
  public void isDefaultValue() {
    assertFalse(EvaluationDetail.fromValue("x", 0, EvaluationReason.off()).isDefaultValue());
    assertFalse(EvaluationDetail.fromValue("x", 0, EvaluationReason.error(CLIENT_NOT_READY)).isDefaultValue());
    assertTrue(EvaluationDetail.fromValue("x", NO_VARIATION, EvaluationReason.error(CLIENT_NOT_READY)).isDefaultValue());
    assertTrue(EvaluationDetail.fromValue("x", -2, EvaluationReason.error(CLIENT_NOT_READY)).isDefaultValue());
  }
  
  @Test
  public void equalInstancesAreEqual() {
    List<List<EvaluationDetail<String>>> testValues = new ArrayList<>();
    for (EvaluationReason reason: new EvaluationReason[] { EvaluationReason.off(), EvaluationReason.fallthrough() }) {
      for (int variation = 0; variation < 2; variation++) {
        for (String value: new String[] { "a", "b" }) {
          List<EvaluationDetail<String>> equalValues = new ArrayList<>();
          for (int i = 0; i < 2; i++) {
            equalValues.add(EvaluationDetail.fromValue(value, variation, reason));
          }
          testValues.add(equalValues);
        }
      }
    }
    TestHelpers.doEqualityTests(testValues);
  }
  
  @Test
  public void commonBooleanValuesAreInterned() {
    for (Object value: new Object[] { LDValue.of(false), LDValue.of(true), Boolean.valueOf(false), Boolean.valueOf(true) }) {
      for (int variationIndex = 0; variationIndex < 2; variationIndex++) {
        for (EvaluationReason reason: new EvaluationReason[] { EvaluationReason.off(), EvaluationReason.fallthrough() }) {
          EvaluationDetail<Object> detail1 = EvaluationDetail.fromValue(value, variationIndex, reason);
          EvaluationDetail<Object> detail2 = EvaluationDetail.fromValue(value, variationIndex, reason);
          assertEquals(value, detail1.getValue());
          assertEquals(variationIndex, detail1.getVariationIndex());
          assertEquals(reason, detail1.getReason());
          assertSame(detail1, detail2);
        }
      }
    }
  }
  
  @Test
  public void simpleStringRepresentation() {
    assertEquals("{x,0,OFF}", EvaluationDetail.fromValue("x", 0, EvaluationReason.off()).toString());
    assertEquals("{\"x\",-1,ERROR(CLIENT_NOT_READY)}", EvaluationDetail.error(CLIENT_NOT_READY, LDValue.of("x")).toString());
  }
}
