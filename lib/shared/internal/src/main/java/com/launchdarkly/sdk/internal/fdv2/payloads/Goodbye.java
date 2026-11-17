package com.launchdarkly.sdk.internal.fdv2.payloads;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.launchdarkly.sdk.json.SerializationException;

import java.io.IOException;
import java.time.Duration;

/**
 * Represents the goodbye event, which indicates that the server is about to disconnect.
 */
public final class Goodbye {
  private final String reason;
  private final Duration protocolFallbackTTL;
  
  /**
   * Constructs a new Goodbye.
   * 
   * @param reason reason for the disconnection
   */
  public Goodbye(String reason) {
    this(reason, null);
  }

  /**
   * Constructs a new Goodbye.
   *
   * @param reason reason for the disconnection
   * @param protocolFallbackTTL fallback TTL, or null if absent; ZERO requests the default TTL
   */
  public Goodbye(String reason, Duration protocolFallbackTTL) {
    this.reason = reason;
    this.protocolFallbackTTL = protocolFallbackTTL;
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
   * Returns the fallback TTL, or null if absent. ZERO requests the default TTL.
   *
   * @return the fallback TTL
   */
  public Duration getProtocolFallbackTTL() {
    return protocolFallbackTTL;
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
    Duration protocolFallbackTTL = null;
    
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
        case "protocolFallbackTTL":
          if (reader.peek() == JsonToken.NUMBER) {
            protocolFallbackTTL = Duration.ofMillis((long) (reader.nextDouble() * 1000));
          } else {
            protocolFallbackTTL = reader.peek() == JsonToken.NULL ? null : Duration.ZERO;
            reader.skipValue();
          }
          break;
        default:
          reader.skipValue();
          break;
        }
      }
      reader.endObject();
      
      return new Goodbye(reason, protocolFallbackTTL);
    } catch (IOException | RuntimeException e) {
      throw new SerializationException(e);
    }
  }
}

