package com.launchdarkly.sdk.internal.fdv2.protocol;

import com.google.gson.JsonElement;
import com.launchdarkly.sdk.json.JsonSerializable;

/**
 * Represents a single event in the FDv2 protocol.
 */
public final class Event implements JsonSerializable {
    private String event;
    private JsonElement data;

    /**
     * Default constructor for JSON deserialization.
     */
    public Event() {}

    /**
     * Constructs an Event with the specified event type and data.
     *
     * @param event the event type
     * @param data the event data (parsed JSON element, lazily deserialized)
     */
    public Event(String event, JsonElement data) {
        this.event = event;
        this.data = data;
    }

    /**
     * Returns the event type.
     *
     * @return the event type
     */
    public String getEvent() {
        return event;
    }

    /**
     * Sets the event type.
     *
     * @param event the event type
     */
    public void setEvent(String event) {
        this.event = event;
    }

    /**
     * Returns the event data.
     *
     * @return the event data (parsed JSON element, lazily deserialized)
     */
    public JsonElement getData() {
        return data;
    }

    /**
     * Sets the event data.
     *
     * @param data the event data (parsed JSON element, lazily deserialized)
     */
    public void setData(JsonElement data) {
        this.data = data;
    }
}

