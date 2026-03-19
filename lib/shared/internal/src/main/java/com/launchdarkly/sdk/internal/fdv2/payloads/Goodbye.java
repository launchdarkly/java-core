package com.launchdarkly.sdk.internal.fdv2.payloads;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.launchdarkly.sdk.json.SerializationException;

import java.io.IOException;

/**
 * Represents the goodbye event, which indicates that the server is about to disconnect.
 */
public final class Goodbye {
  private final String reason;
  
  /**
   * Constructs a new Goodbye.
   * 
   * @param reason reason for the disconnection
   */
  public Goodbye(String reason) {
    this.reason = reason;
  }
  
  /**
   * Returns the reason for the disconnection.
   * 
   * @return the reason
   */
  public String getReason() {
    return reason;
  }
  
  /**
   * Parses a Goodbye from a JsonReader.
   * 
   * @param reader the JSON reader
   * @return the parsed Goodbye
   * @throws SerializationException if the JSON is invalid
   */
  public static Goodbye parse(JsonReader reader) throws SerializationException {
    String reason = null;
    
    try {
      if (reader.peek() != JsonToken.BEGIN_OBJECT) {
        throw new SerializationException("expected object");
      }
      reader.beginObject();
      
      while (reader.peek() != JsonToken.END_OBJECT) {
        String name = reader.nextName();
        switch (name) {
        case "reason":
          reason = reader.nextString();
          break;
        default:
          reader.skipValue();
          break;
        }
      }
      reader.endObject();
      
      return new Goodbye(reason);
    } catch (IOException | RuntimeException e) {
      throw new SerializationException(e);
    }
  }
}

