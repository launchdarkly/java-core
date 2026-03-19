package com.launchdarkly.sdk.internal.fdv2.payloads;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.launchdarkly.sdk.json.SerializationException;

import java.io.IOException;
import java.util.Objects;

/**
 * Represents the delete-object event, which contains a payload object that should be deleted.
 */
public final class DeleteObject {
  private final int version;
  private final String kind;
  private final String key;

  /**
   * Constructs a new DeleteObject.
   *
   * @param version the minimum payload version this change applies to
   * @param kind the kind of object being deleted ("flag" or "segment")
   * @param key the identifier of the object being deleted
   */
  public DeleteObject(int version, String kind, String key) {
    this.version = version;
    this.kind = Objects.requireNonNull(kind, "kind");
    this.key = Objects.requireNonNull(key, "key");
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
   * Returns the kind of the object being deleted ("flag" or "segment").
   *
   * @return the kind
   */
  public String getKind() {
    return kind;
  }

  /**
   * Returns the identifier of the object being deleted.
   *
   * @return the key
   */
  public String getKey() {
    return key;
  }

  /**
   * Parses a DeleteObject from a JsonReader.
   *
   * @param reader the JSON reader
   * @return the parsed DeleteObject
   * @throws SerializationException if the JSON is invalid
   */
  public static DeleteObject parse(JsonReader reader) throws SerializationException {
    Integer version = null;
    String kind = null;
    String key = null;

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
        default:
          reader.skipValue();
          break;
        }
      }
      reader.endObject();

      if (version == null) {
        throw new SerializationException("delete object missing required property 'version'");
      }
      if (kind == null) {
        throw new SerializationException("delete object missing required property 'kind'");
      }
      if (key == null) {
        throw new SerializationException("delete object missing required property 'key'");
      }

      return new DeleteObject(version, kind, key);
    } catch (IOException | RuntimeException e) {
      throw new SerializationException(e);
    }
  }
}

