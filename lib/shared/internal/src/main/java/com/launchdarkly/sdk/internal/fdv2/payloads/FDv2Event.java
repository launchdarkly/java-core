package com.launchdarkly.sdk.internal.fdv2.payloads;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.launchdarkly.sdk.json.SerializationException;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.launchdarkly.sdk.internal.GsonHelpers.gsonInstance;

/**
 * Represents an FDv2 event. This event may be constructed from an SSE event or directly parsed
 * from a polling response.
 */
public final class FDv2Event {
  private static final String EVENT_SERVER_INTENT = "server-intent";
  private static final String EVENT_PUT_OBJECT = "put-object";
  private static final String EVENT_DELETE_OBJECT = "delete-object";
  private static final String EVENT_PAYLOAD_TRANSFERRED = "payload-transferred";
  private static final String EVENT_ERROR = "error";
  private static final String EVENT_GOODBYE = "goodbye";

  private final String eventType;
  private final JsonElement data;

  /**
   * Exception thrown when attempting to deserialize an FDv2Event as the wrong event type.
   */
  public static final class FDv2EventTypeMismatchException extends SerializationException {
    private static final long serialVersionUID = 1L;
    private final String actualEventType;
    private final String expectedEventType;

    public FDv2EventTypeMismatchException(String actualEventType, String expectedEventType) {
      super(String.format("Cannot deserialize event type '%s' as '%s'.", actualEventType, expectedEventType));
      this.actualEventType = actualEventType;
      this.expectedEventType = expectedEventType;
    }

    public String getActualEventType() {
      return actualEventType;
    }

    public String getExpectedEventType() {
      return expectedEventType;
    }
  }

  /**
   * Constructs a new FDv2Event.
   *
   * @param eventType the type of event
   * @param data the event data as a raw JSON element
   */
  public FDv2Event(String eventType, JsonElement data) {
    this.eventType = Objects.requireNonNull(eventType, "eventType");
    this.data = Objects.requireNonNull(data, "data");
  }

  /**
   * Returns the event type.
   *
   * @return the event type
   */
  public String getEventType() {
    return eventType;
  }

  /**
   * Returns the event data as a raw JSON element.
   *
   * @return the event data
   */
  public JsonElement getData() {
    return data;
  }

  /**
   * Parses an FDv2Event from a JsonReader.
   *
   * @param reader the JSON reader
   * @return the parsed FDv2Event
   * @throws SerializationException if the JSON is invalid
   */
  public static FDv2Event parse(JsonReader reader) throws SerializationException {
    String eventType = null;
    JsonElement data = null;

    try {
      if (reader.peek() != JsonToken.BEGIN_OBJECT) {
        throw new SerializationException("expected object");
      }
      reader.beginObject();

      while (reader.peek() != JsonToken.END_OBJECT) {
        String name = reader.nextName();
        switch (name) {
        case "event":
          eventType = reader.nextString();
          break;
        case "data":
          // Store the raw JSON element for later deserialization based on the event type
          data = gsonInstance().fromJson(reader, JsonElement.class);
          break;
        default:
          reader.skipValue();
          break;
        }
      }
      reader.endObject();

      if (eventType == null) {
        throw new SerializationException("event missing required property 'event'");
      }
      if (data == null) {
        throw new SerializationException("event missing required property 'data'");
      }

      return new FDv2Event(eventType, data);
    } catch (IOException | RuntimeException e) {
      throw new SerializationException(e);
    }
  }

  /**
   * Deserializes the data element as a ServerIntent.
   *
   * @return the deserialized ServerIntent
   * @throws SerializationException if the event type does not match or the JSON cannot be deserialized
   */
  public ServerIntent asServerIntent() throws SerializationException {
    return deserializeAs(EVENT_SERVER_INTENT, ServerIntent::parse);
  }

  /**
   * Deserializes the data element as a PutObject.
   */
  public PutObject asPutObject() throws SerializationException {
    return deserializeAs(EVENT_PUT_OBJECT, PutObject::parse);
  }

  /**
   * Deserializes the data element as a DeleteObject.
   */
  public DeleteObject asDeleteObject() throws SerializationException {
    return deserializeAs(EVENT_DELETE_OBJECT, DeleteObject::parse);
  }

  /**
   * Deserializes the data element as a PayloadTransferred.
   */
  public PayloadTransferred asPayloadTransferred() throws SerializationException {
    return deserializeAs(EVENT_PAYLOAD_TRANSFERRED, PayloadTransferred::parse);
  }

  /**
   * Deserializes the data element as an Error.
   */
  public Error asError() throws SerializationException {
    return deserializeAs(EVENT_ERROR, Error::parse);
  }

  /**
   * Deserializes the data element as a Goodbye.
   */
  public Goodbye asGoodbye() throws SerializationException {
    return deserializeAs(EVENT_GOODBYE, Goodbye::parse);
  }

  /**
   * Deserializes an FDv2 polling response containing an "events" array.
   *
   * @param jsonString JSON string with an "events" array
   * @return the list of deserialized events
   * @throws SerializationException if the JSON is malformed or an event cannot be deserialized
   */
  public static List<FDv2Event> parseEventsArray(String jsonString) throws SerializationException {
    JsonObject root;
    try {
      root = gsonInstance().fromJson(jsonString, JsonObject.class);
    } catch (RuntimeException e) {
      throw new SerializationException(e);
    }

    if (root == null || !root.has("events")) {
      throw new SerializationException("FDv2 polling response missing 'events' property");
    }

    JsonElement eventsElement = root.get("events");
    if (!eventsElement.isJsonArray()) {
      throw new SerializationException("FDv2 polling response 'events' is not an array");
    }

    JsonArray eventsArray = eventsElement.getAsJsonArray();
    List<FDv2Event> events = new ArrayList<>(eventsArray.size());
    int index = 0;
    for (JsonElement eventElement : eventsArray) {
      if (eventElement == null || eventElement.isJsonNull()) {
        throw new SerializationException("FDv2 polling response contains null event at index " + index);
      }
      events.add(parseEventElement(eventElement, index));
      index++;
    }
    return events;
  }

  private static FDv2Event parseEventElement(JsonElement element, int index) throws SerializationException {
    if (!element.isJsonObject()) {
      throw new SerializationException("FDv2 polling response event at index " + index + " is not an object");
    }

    JsonObject obj = element.getAsJsonObject();
    JsonElement eventTypeElement = obj.get("event");
    JsonElement dataElement = obj.get("data");

    if (eventTypeElement == null || eventTypeElement.isJsonNull()) {
      throw new SerializationException("event at index " + index + " missing required property 'event'");
    }
    if (dataElement == null || dataElement.isJsonNull()) {
      throw new SerializationException("event at index " + index + " missing required property 'data'");
    }

    return new FDv2Event(eventTypeElement.getAsString(), dataElement);
  }

  private <T> T deserializeAs(String expectedEventType, Parser<T> parser) throws SerializationException {
    if (!expectedEventType.equals(eventType)) {
      throw new FDv2EventTypeMismatchException(eventType, expectedEventType);
    }

    try {
      JsonReader reader = new JsonReader(new StringReader(data.toString()));
      return parser.parse(reader);
    } catch (SerializationException e) {
      throw e;
    } catch (Exception e) {
      throw new SerializationException(e);
    }
  }

  private interface Parser<T> {
    T parse(JsonReader reader) throws Exception;
  }
}

