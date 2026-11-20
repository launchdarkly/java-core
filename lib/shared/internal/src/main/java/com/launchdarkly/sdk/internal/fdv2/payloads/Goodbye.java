package com.launchdarkly.sdk.internal.fdv2.payloads;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.launchdarkly.sdk.json.SerializationException;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * Represents the goodbye event, which indicates that the server is about to disconnect.
 */
public final class Goodbye {
  private final String reason;
  @SerializedName("protocolFallbackTTL")
  @JsonAdapter(FallbackTTLAdapter.class)
  private final Long protocolFallbackTTLMillis;

  private static final class FallbackTTLAdapter extends TypeAdapter<Long> {
    @Override
    public void write(JsonWriter writer, Long value) throws IOException {
      writer.value(BigDecimal.valueOf(value, 3));
    }

    @Override
    public Long read(JsonReader reader) throws IOException {
      if (reader.peek() == JsonToken.NUMBER) {
        return (long) (reader.nextDouble() * 1000);
      }
      // The spec treats a malformed TTL the same as 0: both use the default TTL.
      reader.skipValue();
      return 0L;
    }
  }
  
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
   * @param protocolFallbackTTLMillis fallback TTL in milliseconds, or null if absent; 0 requests the default TTL
   */
  public Goodbye(String reason, Long protocolFallbackTTLMillis) {
    this.reason = reason;
    this.protocolFallbackTTLMillis = protocolFallbackTTLMillis;
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
   * Returns the fallback TTL in milliseconds, or null if absent. 0 requests the default TTL.
   *
   * @return the fallback TTL in milliseconds
   */
  public Long getProtocolFallbackTTLMillis() {
    return protocolFallbackTTLMillis;
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
    Long protocolFallbackTTLMillis = null;
    
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
          if (reader.peek() == JsonToken.NULL) {
            protocolFallbackTTLMillis = null;
            reader.skipValue();
          } else {
            protocolFallbackTTLMillis = new FallbackTTLAdapter().read(reader);
          }
          break;
        default:
          reader.skipValue();
          break;
        }
      }
      reader.endObject();
      
      return new Goodbye(reason, protocolFallbackTTLMillis);
    } catch (IOException | RuntimeException e) {
      throw new SerializationException(e);
    }
  }
}
