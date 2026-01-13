package com.launchdarkly.sdk.internal.fdv2.payloads;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.launchdarkly.sdk.json.SerializationException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.launchdarkly.sdk.internal.GsonHelpers.gsonInstance;

/**
 * Represents the server-intent event, which is the first message sent by flag delivery upon
 * connecting to FDv2. Contains information about how flag delivery intends to handle payloads.
 */
public final class ServerIntent {
  private final List<ServerIntentPayload> payloads;

  /**
   * Constructs a new ServerIntent.
   *
   * @param payloads the payloads the server will be transferring data for
   */
  public ServerIntent(List<ServerIntentPayload> payloads) {
    this.payloads = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(payloads, "payloads")));
  }

  /**
   * Returns the list of payloads the server will be transferring data for.
   *
   * @return the payloads
   */
  public List<ServerIntentPayload> getPayloads() {
    return payloads;
  }

  /**
   * Parses a ServerIntent from a JsonReader.
   *
   * @param reader the JSON reader
   * @return the parsed ServerIntent
   * @throws SerializationException if the JSON is invalid
   */
  public static ServerIntent parse(JsonReader reader) throws SerializationException {
    List<ServerIntentPayload> payloads = null;

    try {
      if (reader.peek() != JsonToken.BEGIN_OBJECT) {
        throw new SerializationException("expected object");
      }
      reader.beginObject();

      while (reader.peek() != JsonToken.END_OBJECT) {
        String name = reader.nextName();
        switch (name) {
        case "payloads":
          JsonArray payloadArray = gsonInstance().fromJson(reader, JsonArray.class);
          payloads = new ArrayList<>(payloadArray.size());
          int index = 0;
          for (JsonElement payloadElement : payloadArray) {
            if (payloadElement == null || payloadElement.isJsonNull()) {
              throw new SerializationException("server-intent contains null payload at index " + index);
            }
            payloads.add(ServerIntentPayload.parse(payloadElement));
            index++;
          }
          break;
        default:
          reader.skipValue();
          break;
        }
      }
      reader.endObject();

      if (payloads == null) {
        throw new SerializationException("server-intent missing required property 'payloads'");
      }

      return new ServerIntent(payloads);
    } catch (IOException | RuntimeException e) {
      throw new SerializationException(e);
    }
  }

  /**
   * Description of server intent to transfer a specific payload.
   */
  public static final class ServerIntentPayload {
    private final String id;
    private final int target;
    private final IntentCode intentCode;
    private final String reason;

    /**
     * Constructs a new ServerIntentPayload.
     *
     * @param id the unique string identifier
     * @param target the target version for the payload
     * @param intentCode how the server intends to operate with respect to sending payload data
     * @param reason reason the server is operating with the provided code
     */
    public ServerIntentPayload(String id, int target, IntentCode intentCode, String reason) {
      this.id = Objects.requireNonNull(id, "id");
      this.target = target;
      this.intentCode = Objects.requireNonNull(intentCode, "intentCode");
      this.reason = Objects.requireNonNull(reason, "reason");
    }

    public String getId() {
      return id;
    }

    public int getTarget() {
      return target;
    }

    public IntentCode getIntentCode() {
      return intentCode;
    }

    public String getReason() {
      return reason;
    }

    static ServerIntentPayload parse(JsonElement element) throws SerializationException {
      String id = null;
      Integer target = null;
      IntentCode intentCode = null;
      String reason = null;

      if (!element.isJsonObject()) {
        throw new SerializationException("expected payload object");
      }

      for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
        String name = entry.getKey();
        JsonElement value = entry.getValue();
        switch (name) {
        case "id":
          id = value.isJsonNull() ? null : value.getAsString();
          break;
        case "target":
          if (!value.isJsonNull()) {
            target = value.getAsInt();
          }
          break;
        case "intentCode":
          if (!value.isJsonNull()) {
            intentCode = IntentCode.parse(value.getAsString());
          }
          break;
        case "reason":
          reason = value.isJsonNull() ? null : value.getAsString();
          break;
        default:
          break;
        }
      }

      if (id == null) {
        throw new SerializationException("server-intent payload missing required property 'id'");
      }
      if (target == null) {
        throw new SerializationException("server-intent payload missing required property 'target'");
      }
      if (intentCode == null) {
        throw new SerializationException("server-intent payload missing required property 'intentCode'");
      }
      if (reason == null) {
        throw new SerializationException("server-intent payload missing required property 'reason'");
      }

      return new ServerIntentPayload(id, target, intentCode, reason);
    }
  }
}

