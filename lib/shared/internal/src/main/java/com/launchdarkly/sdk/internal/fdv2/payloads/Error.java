package com.launchdarkly.sdk.internal.fdv2.payloads;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.launchdarkly.sdk.json.SerializationException;

import java.io.IOException;
import java.util.Objects;

/**
 * Represents the error event, which indicates an error encountered server-side affecting
 * the payload transfer. SDKs must discard partially transferred data. The SDK remains
 * connected and expects the server to recover.
 */
public final class Error {
  private final String id;
  private final String reason;

  /**
   * Constructs a new Error.
   *
   * @param id the unique string identifier of the entity the error relates to
   * @param reason human-readable reason the error occurred
   */
  public Error(String id, String reason) {
    this.id = id;
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  /**
   * Returns the unique string identifier of the entity the error relates to.
   *
   * @return the identifier, or null if not present
   */
  public String getId() {
    return id;
  }

  /**
   * Returns the human-readable reason the error occurred.
   *
   * @return the reason
   */
  public String getReason() {
    return reason;
  }

  /**
   * Parses an Error from a JsonReader.
   *
   * @param reader the JSON reader
   * @return the parsed Error
   * @throws SerializationException if the JSON is invalid
   */
  public static Error parse(JsonReader reader) throws SerializationException {
    String id = null;
    String reason = null;

    try {
      if (reader.peek() != JsonToken.BEGIN_OBJECT) {
        throw new SerializationException("expected object");
      }
      reader.beginObject();

      while (reader.peek() != JsonToken.END_OBJECT) {
        String name = reader.nextName();
        switch (name) {
        case "id":
          id = reader.nextString();
          break;
        case "reason":
          reason = reader.nextString();
          break;
        default:
          reader.skipValue();
          break;
        }
      }
      reader.endObject();

      if (reason == null) {
        throw new SerializationException("error missing required property 'reason'");
      }

      return new Error(id, reason);
    } catch (IOException | RuntimeException e) {
      throw new SerializationException(e);
    }
  }
}

