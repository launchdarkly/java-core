package com.launchdarkly.sdk.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;

import java.io.IOException;

/**
 * A helper class for interoperability with application code that uses
 * <a href="https://github.com/FasterXML/jackson">Jackson</a>.
 * <p>
 * An application that wishes to use Jackson to serialize or deserialize classes from the SDK should
 * configure its {@code ObjectMapper} instance as follows:
 * <pre><code>
 *     import com.launchdarkly.sdk.json.LDJackson;
 *     
 *     ObjectMapper mapper = new ObjectMapper();
 *     mapper.registerModule(LDJackson.module());
 * </code></pre>
 * <p>
 * This causes Jackson to use the correct JSON representation logic (the same that would be used by
 * {@link JsonSerialization}) for any types that have the SDK's {@link JsonSerializable} marker
 * interface, such as {@link LDContext} and {@link LDValue}, regardless of whether they are the
 * top-level object being serialized or are contained in something else such as a collection. It
 * does not affect Jackson's behavior for any other classes.
 * <p>
 * The current implementation is limited in its ability to handle generic types. Currently, the only
 * such type defined by the SDKs is {@link com.launchdarkly.sdk.EvaluationDetail}. You can serialize
 * any {@code EvaluationDetail<T>} instance and it will represent the {@code T} value correctly, but
 * when deserializing, you will always get {@code EvaluationDetail<LDValue>}.
 */
public class LDJackson {
  private LDJackson() {}
  
  /**
   * Returns a Jackson {@code Module} that defines the correct serialization and deserialization
   * behavior for all LaunchDarkly SDK objects that implement {@link JsonSerializable}.
   * <pre><code>
   *     import com.launchdarkly.sdk.json.LDJackson;
   *     
   *     ObjectMapper mapper = new ObjectMapper();
   *     mapper.registerModule(LDJackson.module());
   * </code></pre>
   * @return a {@code Module}
   */
  public static Module module() {
    SimpleModule module = new SimpleModule(LDJackson.class.getName());    
    module.addSerializer(JsonSerializable.class, LDJacksonSerializer.INSTANCE);
    for (Class<?> c: JsonSerialization.getDeserializableClasses()) {
      @SuppressWarnings("unchecked")
      Class<JsonSerializable> cjs = (Class<JsonSerializable>)c;
      module.addDeserializer(cjs, new LDJacksonDeserializer<>(cjs));
    }
    return module;
  }
  
  private static class LDJacksonSerializer extends JsonSerializer<JsonSerializable> {
    static final LDJacksonSerializer INSTANCE = new LDJacksonSerializer();
    
    @Override
    public void serialize(JsonSerializable value, JsonGenerator gen, SerializerProvider serializers)
        throws IOException {
      // Jackson will not call this serializer for a null value
      try (GsonWriterToJacksonGeneratorAdapter adapter = new GsonWriterToJacksonGeneratorAdapter(gen)) {
        JsonSerialization.serializeToGsonInternal(value, value.getClass(), adapter);
      }
    }
  }
  
  private static class LDJacksonDeserializer<T extends JsonSerializable> extends JsonDeserializer<T> {
    private final Class<T> objectClass;
    
    LDJacksonDeserializer(Class<T> objectClass) {
      this.objectClass = objectClass;
    }
    
    @Override
    public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JsonProcessingException {
      try (GsonReaderToJacksonParserAdapter adapter = new GsonReaderToJacksonParserAdapter(p)) {
        try {
          return JsonSerialization.deserializeFromGsonInternal(adapter, objectClass);
        } catch (com.google.gson.JsonParseException e) {
          throw new JsonParseException(p, e.getMessage()); 
        }
      }
    }
  }
  
  static class GsonReaderToJacksonParserAdapter extends GsonReaderAdapter {
    private final JsonParser parser;
    private boolean atToken = true;
    
    GsonReaderToJacksonParserAdapter(JsonParser parser) {
      this.parser = parser;
    }

    @Override
    public void beginArray() throws IOException {
      requireToken(JsonToken.START_ARRAY, JsonToken.START_ARRAY, "array");
    }

    @Override
    public void beginObject() throws IOException {
      requireToken(JsonToken.START_OBJECT, JsonToken.START_OBJECT, "object");
    }

    @Override
    public void endArray() throws IOException {
      requireToken(JsonToken.END_ARRAY, JsonToken.END_ARRAY, "end of array");
    }

    @Override
    public void endObject() throws IOException {
      requireToken(JsonToken.END_OBJECT, JsonToken.END_OBJECT, "end of object");
    }

    @Override
    public boolean hasNext() throws IOException {
      JsonToken t = peekToken();
      return t != JsonToken.END_ARRAY && t != JsonToken.END_OBJECT;
    }

    @Override
    public boolean nextBoolean() throws IOException {
      requireToken(JsonToken.VALUE_FALSE, JsonToken.VALUE_TRUE, "boolean");
      return parser.getBooleanValue();
    }

    @Override
    public double nextDouble() throws IOException {
      requireToken(JsonToken.VALUE_NUMBER_FLOAT, JsonToken.VALUE_NUMBER_INT, "number");
      return parser.getDoubleValue();
    }

    @Override
    public int nextInt() throws IOException {
      requireToken(JsonToken.VALUE_NUMBER_FLOAT, JsonToken.VALUE_NUMBER_INT, "number");
      return parser.getIntValue();
    }

    @Override
    public long nextLong() throws IOException {
      requireToken(JsonToken.VALUE_NUMBER_FLOAT, JsonToken.VALUE_NUMBER_INT, "number");
      return parser.getLongValue();
    }

    @Override
    public String nextName() throws IOException {
      requireToken(JsonToken.FIELD_NAME, JsonToken.FIELD_NAME, "property name");
      return parser.getCurrentName();
    }

    @Override
    public void nextNull() throws IOException {
      requireToken(JsonToken.VALUE_NULL, JsonToken.VALUE_NULL, "null");
    }

    @Override
    public String nextString() throws IOException {
      requireToken(JsonToken.VALUE_STRING, JsonToken.VALUE_NULL, "string");
      return parser.getValueAsString();
    }

    @Override
    public void skipValue() throws IOException {
      consumeToken();
      parser.skipChildren();
    }

    @Override
    protected int peekInternal() throws IOException {
      JsonToken t = peekToken();
      if (t == null) {
        return com.google.gson.stream.JsonToken.END_DOCUMENT.ordinal();
      }
      com.google.gson.stream.JsonToken gt;
      switch (t) {
      case END_ARRAY:
        gt = com.google.gson.stream.JsonToken.END_ARRAY;
        break;
      case END_OBJECT:
        gt = com.google.gson.stream.JsonToken.END_OBJECT;
        break;
      case FIELD_NAME:
        gt = com.google.gson.stream.JsonToken.NAME;
        break;
      case NOT_AVAILABLE:
        gt = com.google.gson.stream.JsonToken.END_DOCUMENT; // COVERAGE: shouldn't be reachable
        break;
      case START_ARRAY:
        gt = com.google.gson.stream.JsonToken.BEGIN_ARRAY;
        break;
      case START_OBJECT:
        gt = com.google.gson.stream.JsonToken.BEGIN_OBJECT;
        break;
      case VALUE_FALSE:
        gt = com.google.gson.stream.JsonToken.BOOLEAN;
        break;
      case VALUE_NULL:
        gt = com.google.gson.stream.JsonToken.NULL;
        break;
      case VALUE_NUMBER_FLOAT:
        gt = com.google.gson.stream.JsonToken.NUMBER;
        break;
      case VALUE_NUMBER_INT:
        gt = com.google.gson.stream.JsonToken.NUMBER;
        break;
      case VALUE_STRING:
        gt = com.google.gson.stream.JsonToken.STRING;
        break;
      case VALUE_TRUE:
        gt = com.google.gson.stream.JsonToken.BOOLEAN;
        break;
      default:
        gt = com.google.gson.stream.JsonToken.END_DOCUMENT; // COVERAGE: shouldn't be reachable
      }
      return gt.ordinal();
    }
    
    private void requireToken(JsonToken type, JsonToken alternateType, String expectedDesc) throws IOException {
      JsonToken t = consumeToken();
      if (t != type && t != alternateType) {
        throw new JsonParseException(parser, "expected " + expectedDesc);
      }
    }
    
    private JsonToken peekToken() throws IOException {
      if (!atToken) {
        atToken = true;
        return parser.nextToken();
      }
      return parser.currentToken();
    }
    
    private JsonToken consumeToken() throws IOException {
      if (atToken) {
        atToken = false;
        return parser.currentToken();
      }
      return parser.nextToken();
    }
  }
  
  static class GsonWriterToJacksonGeneratorAdapter extends GsonWriterAdapter {
    private final JsonGenerator gen;
    
    GsonWriterToJacksonGeneratorAdapter(JsonGenerator gen) {
      this.gen = gen;
    }

    @Override
    protected void beginArrayInternal() throws IOException {
      gen.writeStartArray();
    }

    @Override
    protected void beginObjectInternal() throws IOException {
      gen.writeStartObject();
    }

    @Override
    protected void endArrayInternal() throws IOException {
      gen.writeEndArray();
    }

    @Override
    protected void endObjectInternal() throws IOException {
      gen.writeEndObject();
    }

    @Override
    protected void jsonValueInternal(String value) throws IOException {
      gen.writeRawValue(value);
    }

    @Override
    protected void nameInternal(String name) throws IOException {
      gen.writeFieldName(name);
    }

    @Override
    protected void valueInternalNull() throws IOException {
      gen.writeNull();
    }

    @Override
    protected void valueInternalBool(boolean value) throws IOException {
      gen.writeBoolean(value);
    }

    @Override
    protected void valueInternalDouble(double value) throws IOException {
      gen.writeNumber(value);
    }

    @Override
    protected void valueInternalLong(long value) throws IOException {
      gen.writeNumber(value);
    }

    @Override
    protected void valueInternalString(String value) throws IOException {
      gen.writeString(value);
    }
  }
}
