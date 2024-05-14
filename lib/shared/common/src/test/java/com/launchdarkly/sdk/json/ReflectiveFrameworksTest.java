package com.launchdarkly.sdk.json;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.launchdarkly.sdk.BaseTest;
import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDValue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static com.launchdarkly.sdk.json.JsonTestHelpers.assertJsonEquals;
import static com.launchdarkly.sdk.json.JsonTestHelpers.configureGson;
import static com.launchdarkly.sdk.json.JsonTestHelpers.configureJacksonMapper;
import static org.junit.Assert.assertEquals;

@SuppressWarnings("javadoc")
public class ReflectiveFrameworksTest extends BaseTest {
  // Test classes like LDValueJsonSerializationTest already cover using all available JSON
  // frameworks to serialize and deserialize instances of our classes. This one tests the
  // ability of Gson and Jackson, when properly configured, to get the right serialization
  // or deserialization reflectively when we do not specify the desired class up front -
  // that is, when one of our types is used inside another data structure.
  //
  // Since we've already verified the serializations for each of our types separately, we
  // don't need to repeat these tests for all of them. We will just use LDValue to stand in
  // for all the non-generic types, and EvaluationDetail as a generic type.
  
  private static final LDValue TOP_LEVEL_VALUE = LDValue.of("x");
  private static final String EXPECTED_JSON =
      "{\"topLevelValue\":\"x\",\"mapOfValues\":{\"a\":1,\"b\":[2,3]}," +
      "\"detailValue\":{\"value\":1000,\"variationIndex\":1,\"reason\":{\"kind\":\"OFF\"}}," +
      "\"stringDetailValue\":{\"value\":\"x\",\"variationIndex\":1,\"reason\":{\"kind\":\"OFF\"}}}";
  
  @Test
  public void gsonSerializesTypeContainingOurType() {
    ObjectContainingValues o = new ObjectContainingValues(
        TOP_LEVEL_VALUE, makeMapOfValues(), makeDetailValue(), makeStringDetailValue());
    assertJsonEquals(EXPECTED_JSON, configureGson().toJson(o));
  }
  
  @Test
  public void gsonDeserializesTypeContainingOurTypes() {
    ObjectContainingValues o = configureGson().fromJson(EXPECTED_JSON, ObjectContainingValues.class);
    assertEquals(TOP_LEVEL_VALUE, o.topLevelValue);
    assertEquals(makeMapOfValues(), o.mapOfValues);
    assertEquals(makeDetailValue(), o.detailValue);
    assertEquals(makeStringDetailValue(), o.stringDetailValue);
  }
  
  @Test
  public void jacksonSerializesTypeContainingOurType() throws Exception {
    ObjectContainingValues o = new ObjectContainingValues(
        TOP_LEVEL_VALUE, makeMapOfValues(),makeDetailValue(), makeStringDetailValue());
    assertJsonEquals(EXPECTED_JSON, configureJacksonMapper().writeValueAsString(o));
  }

  @Test
  public void jacksonDeserializesTypeContainingOurTypes() throws Exception {
    ObjectContainingValues o = configureJacksonMapper().readValue(EXPECTED_JSON, ObjectContainingValues.class);
    assertEquals(TOP_LEVEL_VALUE, o.topLevelValue);
    assertEquals(makeMapOfValues(), o.mapOfValues);
    assertEquals(makeDetailValue(), o.detailValue);
    
    // The current implementation of the Jackson adapter cannot see generic type parameters; the
    // EvaluationDetail<String> field will be deserialized as EvaluationDetail<LDValue>. This limitation
    // is documented in EvaluationDetail and LDJackson.
    //assertEquals(makeStringDetailValue(), o.stringDetailValue);
    assertEquals(EvaluationDetail.fromValue(
        LDValue.of(makeStringDetailValue().getValue()),
          makeStringDetailValue().getVariationIndex(), makeStringDetailValue().getReason()),
        o.stringDetailValue);
  }
  
  private static Map<String, LDValue> makeMapOfValues() {
    Map<String, LDValue> m = new HashMap<>();
    m.put("a", LDValue.of(1));
    m.put("b", LDValue.buildArray().add(2).add(3).build());
    return m;
  }
  
  private static EvaluationDetail<LDValue> makeDetailValue() {
    return EvaluationDetail.fromValue(LDValue.of(1000), 1, EvaluationReason.off());
  }
  
  private static EvaluationDetail<String> makeStringDetailValue() {
    // What we're testing here is that deserializing with a target type of EvaluationDetail<String>,
    // when that type signature is knowable via reflection, causes it to parse the value property as
    // a String rather than an LDValue.
    return EvaluationDetail.fromValue("x", 1, EvaluationReason.off());
  }
  
  private static final class ObjectContainingValues {
    public LDValue topLevelValue;
    public Map<String, LDValue> mapOfValues;
    public EvaluationDetail<LDValue> detailValue;
    public EvaluationDetail<String> stringDetailValue;
    
    @JsonCreator
    public ObjectContainingValues(@JsonProperty("topLevelValue") LDValue topLevelValue,
        @JsonProperty("mapOfValues") Map<String, LDValue> mapOfValues,
        @JsonProperty("detailValue") EvaluationDetail<LDValue> detailValue,
        @JsonProperty("stringDetailValue") EvaluationDetail<String> stringDetailValue) {
      this.topLevelValue = topLevelValue;
      this.mapOfValues = mapOfValues;
      this.detailValue = detailValue;
      this.stringDetailValue = stringDetailValue;
    }
  }
}
