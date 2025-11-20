package com.launchdarkly.sdk.internal.fdv2.protocol;

import com.launchdarkly.sdk.json.JsonSerializable;

/**
 * Represents an error in the FDv2 protocol.
 */
public final class Error implements JsonSerializable {
    private String id;
    private String reason;

    /**
     * Default constructor for JSON deserialization.
     */
    public Error() {}

    /**
     * Constructs an Error with the specified properties.
     *
     * @param id the unique string identifier of the entity the error relates to
     * @param reason the human readable reason the error occurred
     */
    public Error(String id, String reason) {
        this.id = id;
        this.reason = reason;
    }

    /**
     * Returns the unique string identifier of the entity the error relates to.
     *
     * @return the unique string identifier, or null if not set
     */
    public String getId() {
        return id;
    }

    /**
     * Sets the unique string identifier of the entity the error relates to.
     *
     * @param id the unique string identifier
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * Returns the human readable reason the error occurred.
     *
     * @return the human readable reason the error occurred
     */
    public String getReason() {
        return reason;
    }

    /**
     * Sets the human readable reason the error occurred.
     *
     * @param reason the human readable reason the error occurred
     */
    public void setReason(String reason) {
        this.reason = reason;
    }
}

