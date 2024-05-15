package com.launchdarkly.sdk.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.launchdarkly.sdk.AttributeRef;
import com.launchdarkly.sdk.ContextKind;
import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDUser;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.UserAttribute;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Helper methods for JSON serialization of SDK classes.
 * <p>
 * While the LaunchDarkly Java-based SDKs have used <a href="https://github.com/google/gson">Gson</a>
 * internally in the past, they may not always do so-- and even if they do, some SDK distributions may
 * embed their own copy of Gson with modified (shaded) class names so that it does not conflict with
 * any Gson instance elsewhere in the classpath. For both of those reasons, applications should not
 * assume that {@code Gson.toGson()} and {@code Gson.fromGson()}-- or any other JSON framework that is
 * based on reflection-- will work correctly for SDK classes, whose correct JSON representations do
 * not necessarily correspond to their internal field layout. Instead, they should always use one of
 * the following:
 * <ol>
 * <li> The {@link JsonSerialization} methods.
 * <li> A Gson instance that has been configured with {@link LDGson}.
 * <li> For {@link LDValue}, you may also use the convenience methods {@link LDValue#toJsonString()} and
 * {@link LDValue#parse(String)}.
 * </ol>
 */
public abstract class JsonSerialization {
  private JsonSerialization() {}
  
  static final List<Class<? extends JsonSerializable>> knownDeserializableClasses = new ArrayList<>();
  
  // This Gson instance has serializeNulls enabled because we want the decision of whether to include
  // a null property value to be left up to our own serializers. The default behavior would mean that
  // the GsonWriter would not allow us to write a null property value ever. 
  private static final Gson gson = new GsonBuilder().serializeNulls().create();
  
  /**
   * Converts an object to its JSON representation.
   * <p>
   * This is only usable for classes that have the {@link JsonSerializable} marker interface,
   * indicating that the SDK knows how to serialize them.
   * 
   * @param <T> class of the object being serialized
   * @param instance the instance to serialize
   * @return the object's JSON encoding as a string
   */
  public static <T extends JsonSerializable> String serialize(T instance) {
    return serializeInternal(instance);
  }
  
  // We use this internally in situations where generic type checking isn't desirable
  static String serializeInternal(Object instance) {
    return gson.toJson(instance);
  }
  
  /**
   * Parses an object from its JSON representation.
   * <p>
   * This is only usable for classes that have the {@link JsonSerializable} marker interface,
   * indicating that the SDK knows how to serialize them.
   * <p>
   * The current implementation is limited in its ability to handle generic types. Currently, the only
   * such type defined by the SDKs is {@link com.launchdarkly.sdk.EvaluationDetail}. You can serialize
   * any {@code EvaluationDetail<T>} instance and it will represent the {@code T} value correctly, but
   * when deserializing, you will always get {@code EvaluationDetail<LDValue>}.
   * 
   * @param <T> class of the object being deserialized
   * @param json the object's JSON encoding as a string
   * @param objectClass class of the object being deserialized
   * @return the deserialized instance
   * @throws SerializationException if the JSON encoding was invalid
   */
  public static <T extends JsonSerializable> T deserialize(String json, Class<T> objectClass) throws SerializationException {
    return deserializeInternal(json, objectClass);
  }
  
  // We use this internally in situations where generic type checking isn't desirable
  static <T> T deserializeInternal(String json, Class<T> objectClass) throws SerializationException {
    if (json == null || json.isEmpty()) {
      // Annoyingly, Gson tolerates a totally empty input string and considers it equivalent to null,
      // but that isn't a valid JSON document.
      throw new SerializationException("input string was null/empty");
    }
    try {
      return gson.fromJson(json, objectClass);
    } catch (Exception e) {
      throw new SerializationException(e);
    }
  }

  // Used internally to delegate to gson.toJson() in a way that will work correctly regardless of
  // whether we're shading the Gson types or not.
  //
  // The issue is this. In the Java SDK, all references to Gson types anywhere in the SDK *except*
  // in the LDGson class will have their packages rewritten from com.google.gson to
  // com.launchdarkly.shaded.com.google.gson. That's the whole reason GsonWriterAdapter exists.
  // However, the shading logic is not quite smart enough to adjust method signatures that have
  // already been copied into non-shaded classes, so if the LDGson code (which is immune from
  // shading) tries to call any methods on JsonSerialization.gson (which was originally an
  // instance of c.g.gson.Gson, but now is an instance of c.l.s.c.g.gson.Gson)-- or tries to call
  // any method that took a parameter of type c.g.gson.stream.JsonWriter, but has since been
  // rewritten to take a parameter of type c.l.s.c.g.gson.stream.JsonWriter-- the call will fail
  // because the actual method signature doesn't match what the caller expected.
  //
  // The solution is to add this delegating method whose external surface doesn't contain any
  // references to classes whose package names will be rewritten; while JsonSerialization and
  // GsonWriterAdapter will have code *inside* them modified by shading, their own signatures
  // won't change.
  static void serializeToGsonInternal(Object value, Class<?> type, GsonWriterAdapter writer) {
    gson.toJson(value, type, writer);
  }
  
  // See comment on serializeToGsonInternal.
  static <T> T deserializeFromGsonInternal(GsonReaderAdapter adapter, Type type) {
    return gson.fromJson(adapter, type);
  }
  
  /**
   * Internal method to return all of the classes that we should have a custom deserializer for.
   * <p>
   * The reason for this method is for some JSON frameworks, such as Jackson, it is not possible to
   * register a general deserializer for a base type like JsonSerializable and have it be called by
   * the framework when someone wants to deserialize some concrete type descended from that base type.
   * Instead, we must register a deserializer for each of the latter.
   * <p>
   * Since the SDKs may define their own JsonSerializable types that are not in this common library,
   * there is a reflection-based mechanism for discovering those: the SDK may define a class called
   * com.launchdarkly.sdk.json.SdkSerializationExtensions, with a static method whose signature is
   * the same as this method, and whatever it returns will be added to this return value.
   * <p>
   * In the case of a base class like LDValue where the deserializer is for the base class (because
   * application code does not know about the subclasses) and implements its own polymorphism, we
   * should only list the base class.
   * 
   * @return classes we should have a custom deserializer for
   */
  static Iterable<Class<? extends JsonSerializable>> getDeserializableClasses() {
    // COVERAGE: This method should be excluded from code coverage analysis, because we can't test the
    // reflective SDK extension logic inside this repo. SdkSerializationExtensions is not defined in this
    // repo by necessity, and if we defined it in the test code then we would not be able to test the
    // default case where it *doesn't* exist. This functionality is tested in the Java SDK.
    synchronized (knownDeserializableClasses) {
      if (knownDeserializableClasses.isEmpty()) {
        knownDeserializableClasses.add(AttributeRef.class);
        knownDeserializableClasses.add(ContextKind.class);
        knownDeserializableClasses.add(EvaluationReason.class);
        knownDeserializableClasses.add(EvaluationDetail.class);
        knownDeserializableClasses.add(LDContext.class);
        knownDeserializableClasses.add(LDUser.class);
        knownDeserializableClasses.add(LDValue.class);
        knownDeserializableClasses.add(UserAttribute.class);
        
        // Use reflection to find any additional classes provided by an SDK; if there are none or if
        // this fails for any reason, don't worry about it
        try {
          Class<?> sdkExtensionsClass = Class.forName("com.launchdarkly.sdk.json.SdkSerializationExtensions");
          Method method = sdkExtensionsClass.getMethod("getDeserializableClasses");
          @SuppressWarnings("unchecked")
          Iterable<Class<? extends JsonSerializable>> sdkClasses =
              (Iterable<Class<? extends JsonSerializable>>) method.invoke(null);
          for (Class<? extends JsonSerializable> c: sdkClasses) {
            knownDeserializableClasses.add(c);
          }
        } catch (Exception e) {} 
      }
    }
    
    return knownDeserializableClasses;
  }
}
