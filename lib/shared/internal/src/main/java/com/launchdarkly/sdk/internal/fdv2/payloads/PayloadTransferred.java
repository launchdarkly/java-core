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
   * @param version the payload version; deprecated and without meaning (see {@link #getVersion()})
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
   * Returns the payload's {@code version} field, or 0 if it was absent or not an integer.
   * <p>
   * The field is deprecated and has no meaning: SDKs must not use it, and must treat it as absent
   * (FDV2PL 3.4). LaunchDarkly sends 0 only for older SDKs that require it.
   *
   * @return the version, or 0
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
    int version = 0; // optional and deprecated: absent means 0

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
          version = readOptionalInt(reader);
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

      return new PayloadTransferred(state, version);
    } catch (IOException | RuntimeException e) {
      throw new SerializationException(e);
    }
  }

  /** Reads a whole number that fits in an int; any other value is skipped and read as 0. */
  private static int readOptionalInt(JsonReader reader) throws IOException {
    if (reader.peek() != JsonToken.NUMBER) {
      reader.skipValue();
      return 0;
    }
    double d = reader.nextDouble();
    return d == Math.rint(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE ? (int) d : 0;
  }
}
