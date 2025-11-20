package com.launchdarkly.sdk.internal.fdv2.protocol;

import com.launchdarkly.sdk.json.JsonSerializable;

import java.util.List;

import static java.util.Collections.emptyList;

/**
 * Represents server intent data containing a list of payload intents.
 */
public final class ServerIntentData implements JsonSerializable {
    private List<PayloadIntent> payloads;

    /**
     * Default constructor for JSON deserialization.
     */
    public ServerIntentData() {}

    /**
     * Constructs a ServerIntentData with the specified payloads.
     *
     * @param payloads the list of payload intents
     */
    public ServerIntentData(List<PayloadIntent> payloads) {
        this.payloads = payloads;
    }

    /**
     * Returns the list of payload intents.
     *
     * @return the list of payload intents (never null)
     */
    public List<PayloadIntent> getPayloads() {
        return payloads == null ? emptyList() : payloads;
    }

    /**
     * Sets the list of payload intents.
     *
     * @param payloads the list of payload intents
     */
    public void setPayloads(List<PayloadIntent> payloads) {
        this.payloads = payloads;
    }

    /**
     * Validates that all required fields are present.
     *
     * @throws IllegalArgumentException if any required field is missing
     */
    public void validate() {
        if (payloads == null) {
            throw new IllegalArgumentException("Required field missing");
        }
        for (PayloadIntent payload : payloads) {
            if (payload != null) {
                payload.validate();
            }
        }
    }
}

