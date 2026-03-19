package com.launchdarkly.sdk.internal.fdv2.payloads;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.launchdarkly.sdk.json.SerializationException;

import java.io.IOException;
import java.util.Objects;

/**
 * Represents the payload-transferred event, which is sent after all messages for a payload update
 * have been transmitted.
 */
public final class PayloadTransferred {
  private final String state;
  private final int version;

  /**
   * Constructs a new PayloadTransferred.
   *
   * @param state the unique string representing the payload state
   * @param version the version of the payload that was transferred to the client
   */
  public PayloadTransferred(String state, int version) {
    this.state = Objects.requireNonNull(state, "state");
    this.version = version;
  }

  /**
   * Returns the unique string representing the payload state.
   *
   * @return the state
   */
  public String getState() {
    return state;
  }

  /**
   * Returns the version of the payload that was transferred.
   *
   * @return the version
   */
  public int getVersion() {
    return version;
  }

  /**
   * Parses a PayloadTransferred from a JsonReader.
   *
   * @param reader the JSON reader
   * @return the parsed PayloadTransferred
   * @throws SerializationException if the JSON is invalid
   */
  public static PayloadTransferred parse(JsonReader reader) throws SerializationException {
    String state = null;
    Integer version = null;

    try {
      if (reader.peek() != JsonToken.BEGIN_OBJECT) {
        throw new SerializationException("expected object");
      }
      reader.beginObject();

      while (reader.peek() != JsonToken.END_OBJECT) {
        String name = reader.nextName();
        switch (name) {
        case "state":
          state = reader.nextString();
          break;
        case "version":
          version = reader.nextInt();
          break;
        default:
          reader.skipValue();
          break;
        }
      }
      reader.endObject();

      if (state == null) {
        throw new SerializationException("payload-transferred missing required property 'state'");
      }
      if (version == null) {
        throw new SerializationException("payload-transferred missing required property 'version'");
      }

      return new PayloadTransferred(state, version);
    } catch (IOException | RuntimeException e) {
      throw new SerializationException(e);
    }
  }
}

