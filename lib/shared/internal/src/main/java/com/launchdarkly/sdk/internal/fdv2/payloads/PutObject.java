package com.launchdarkly.sdk.internal.fdv2.payloads;

import com.google.gson.JsonElement;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.launchdarkly.sdk.json.SerializationException;

import java.io.IOException;
import java.util.Objects;

import static com.launchdarkly.sdk.internal.GsonHelpers.gsonInstance;

/**
 * Represents the put-object event, which contains a payload object that should be accepted with
 * upsert semantics. The object can be either a flag or a segment.
 */
public final class PutObject {
  private final int version;
  private final String kind;
  private final String key;
  private final JsonElement object;

  /**
   * Constructs a new PutObject.
   *
   * @param version the minimum payload version this change applies to
   * @param kind the kind of object being PUT ("flag" or "segment")
   * @param key the identifier of the object
   * @param object the raw JSON object being PUT
   */
  public PutObject(int version, String kind, String key, JsonElement object) {
    this.version = version;
    this.kind = Objects.requireNonNull(kind, "kind");
    this.key = Objects.requireNonNull(key, "key");
    this.object = Objects.requireNonNull(object, "object");
  }

  /**
   * Returns the minimum payload version this change applies to.
   *
   * @return the version
   */
  public int getVersion() {
    return version;
  }

  /**
   * Returns the kind of the object being PUT ("flag" or "segment").
   *
   * @return the kind
   */
  public String getKind() {
    return kind;
  }

  /**
   * Returns the identifier of the object.
   *
   * @return the key
   */
  public String getKey() {
    return key;
  }

  /**
   * Returns the raw JSON object being PUT.
   *
   * @return the object
   */
  public JsonElement getObject() {
    return object;
  }

  /**
   * Parses a PutObject from a JsonReader.
   *
   * @param reader the JSON reader
   * @return the parsed PutObject
   * @throws SerializationException if the JSON is invalid
   */
  public static PutObject parse(JsonReader reader) throws SerializationException {
    Integer version = null;
    String kind = null;
    String key = null;
    JsonElement object = null;

    try {
      if (reader.peek() != JsonToken.BEGIN_OBJECT) {
        throw new SerializationException("expected object");
      }
      reader.beginObject();

      while (reader.peek() != JsonToken.END_OBJECT) {
        String name = reader.nextName();
        switch (name) {
        case "version":
          version = reader.nextInt();
          break;
        case "kind":
          kind = reader.nextString();
          break;
        case "key":
          key = reader.nextString();
          break;
        case "object":
          object = gsonInstance().fromJson(reader, JsonElement.class);
          break;
        default:
          reader.skipValue();
          break;
        }
      }
      reader.endObject();

      if (version == null) {
        throw new SerializationException("put object missing required property 'version'");
      }
      if (kind == null) {
        throw new SerializationException("put object missing required property 'kind'");
      }
      if (key == null) {
        throw new SerializationException("put object missing required property 'key'");
      }
      if (object == null) {
        throw new SerializationException("put object missing required property 'object'");
      }

      return new PutObject(version, kind, key, object);
    } catch (IOException | RuntimeException e) {
      throw new SerializationException(e);
    }
  }
}

