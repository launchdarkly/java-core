package com.launchdarkly.sdk.internal.fdv2.payloads;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.launchdarkly.sdk.json.SerializationException;

import java.io.IOException;

/**
 * Represents the intent code indicating how the server intends to transfer data.
 */
public enum IntentCode {
  NONE("none"),
  TRANSFER_FULL("xfer-full"),
  TRANSFER_CHANGES("xfer-changes");

  private final String stringValue;

  IntentCode(String stringValue) {
    this.stringValue = stringValue;
  }

  /**
   * Returns the string representation of the intent code.
   *
   * @return the string value
   */
  public String getStringValue() {
    return stringValue;
  }

  /**
   * Parses a string into an IntentCode.
   *
   * @param value the string value
   * @return the parsed IntentCode
   * @throws SerializationException if the value is unknown or null
   */
  public static IntentCode parse(String value) throws SerializationException {
    if (value == null) {
      throw new SerializationException("intentCode missing required value");
    }

    switch (value) {
    case "none":
      return NONE;
    case "xfer-full":
      return TRANSFER_FULL;
    case "xfer-changes":
      return TRANSFER_CHANGES;
    default:
      throw new SerializationException("unknown intent code: " + value);
    }
  }

  @Override
  public String toString() {
    return stringValue;
  }

  /**
   * Gson TypeAdapter for serializing and deserializing IntentCode.
   * Serializes using the string value (e.g., "xfer-full") rather than the enum name.
   */
  public static final class IntentCodeTypeAdapter extends TypeAdapter<IntentCode> {
    @Override
    public void write(JsonWriter out, IntentCode value) throws IOException {
      if (value == null) {
        out.nullValue();
      } else {
        out.value(value.getStringValue());
      }
    }

    @Override
    public IntentCode read(JsonReader in) throws IOException {
      String value = in.nextString();
      try {
        return IntentCode.parse(value);
      } catch (SerializationException e) {
        throw new IOException(e);
      }
    }
  }
}


