package com.launchdarkly.sdk.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSyntaxException;
import com.launchdarkly.sdk.BaseTest;
import com.launchdarkly.sdk.LDValue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

@SuppressWarnings("javadoc")
public abstract class JsonTestHelpers extends BaseTest {
  static final Gson gson = new GsonBuilder().serializeNulls().create();

  // Note that when we verify the behavior of Gson with LDGson in this project's unit tests, that
  // is not an adequate test for whether the adapters will work in the Java SDK where there is the
  // additional issue of Gson types being shaded. The Java SDK project must do its own basic tests
  // of Gson interoperability using the shaded SDK jar. But the tests in this project still prove
  // that the adapters work correctly if Gson actually uses them.
  
  public static Gson configureGson() {
    return new GsonBuilder().serializeNulls().registerTypeAdapterFactory(LDGson.typeAdapters()).create();
  }
  
  public static ObjectMapper configureJacksonMapper() {
    ObjectMapper jacksonMapper = new ObjectMapper();
    jacksonMapper.registerModule(LDJackson.module());
    return jacksonMapper;
  }
  
  public static <T extends JsonSerializable> void verifySerializeAndDeserialize(T instance, String expectedJsonString) throws Exception {
    verifySerialize(instance, expectedJsonString);
    verifyDeserialize(instance, expectedJsonString);
  }
  
  public static <T extends JsonSerializable> void verifySerialize(T instance, String expectedJsonString) throws Exception {
    // All subclasses of Gson's JsonElement implement deep equality for equals(). So does our own LDValue,
    // but since some of our tests are testing LDValue itself, we can't assume that its behavior is correct. 
    assertJsonEquals(expectedJsonString, JsonSerialization.serialize(instance));
    
    assertJsonEquals(expectedJsonString, configureGson().toJson(instance));
    
    assertJsonEquals(expectedJsonString, configureJacksonMapper().writeValueAsString(instance));
  }

  public static <T extends JsonSerializable> void verifySerializationDeserializesTo(T instance, T resultInstance) throws Exception {
    assertEquals(resultInstance,
        JsonSerialization.deserialize(JsonSerialization.serialize(instance), resultInstance.getClass()));
    
    assertEquals(resultInstance,
        configureGson().fromJson(configureGson().toJson(instance), resultInstance.getClass()));
    
    assertEquals(resultInstance,
        configureJacksonMapper().readValue(configureJacksonMapper().writeValueAsString(instance), resultInstance.getClass()));
  }
  
  @SuppressWarnings("unchecked")
  public static <T extends JsonSerializable> void verifyDeserialize(T instance, String expectedJsonString) throws Exception {
    // Special handling here because in real life you wouldn't be trying to deserialize something as for
    // instance LDValueNumber, because those subclasses aren't public; you have to refer to the base class.
    Class<T> objectClass = (Class<T>)instance.getClass();
    if (LDValue.class.isAssignableFrom(objectClass)) {
      objectClass = (Class<T>)LDValue.class;
    }
    
    T instance1 = JsonSerialization.deserialize(expectedJsonString, objectClass);
    assertEquals(instance, instance1);
    
    T instance2 = configureGson().fromJson(expectedJsonString, objectClass);
    assertEquals(instance, instance2);
    
    T instance3 = configureJacksonMapper().readValue(expectedJsonString, objectClass);
    assertEquals(instance, instance3);
  }

  public static <T extends JsonSerializable> void verifyDeserializeInvalidJson(Class<T> objectClass, String invalidJsonString)
      throws Exception {
    try {
      JsonSerialization.deserialize(invalidJsonString, objectClass);
      fail("expected SerializationException");
    } catch (SerializationException e) {}
    try {
      configureGson().fromJson(invalidJsonString, objectClass);
      fail("expected JsonParseException from Gson");
    } catch (JsonParseException e) {}
    try {
      configureJacksonMapper().readValue(invalidJsonString, objectClass);
      fail("expected JsonProcessingException from Jackson");
    } catch (JsonProcessingException e) {}    
  }
  
  public static void assertJsonEquals(String expectedJsonString, String actualJsonString) {
    try {
      JsonElement actualParsed = parseElement(actualJsonString);
      assertEquals(parseElement(expectedJsonString), actualParsed);
    } catch (JsonSyntaxException e) {
      fail("expected JSON: " + expectedJsonString + ", but got malformed JSON: " + actualJsonString);
    }
  }
  
  public static JsonElement parseElement(String jsonString) {
    return gson.fromJson(jsonString, JsonElement.class);
  }
  
  public static LDValue basicArrayValue() {
    return LDValue.buildArray().add(2).add("x").build();
  }
  
  public static LDValue basicObjectValue() {
    return LDValue.buildObject().put("x", 2).build();
  }
  
  public static LDValue nestedArrayValue() {
    return LDValue.buildArray().add(3).add(basicArrayValue()).add(4).add(basicObjectValue()).add(5).build();
  }
  
  public static LDValue nestedObjectValue() {
    return LDValue.buildObject().put("a", 1).put("b", basicArrayValue()).put("c", 2)
          .put("d", basicObjectValue()).put("e", 5).build();
  }
}
