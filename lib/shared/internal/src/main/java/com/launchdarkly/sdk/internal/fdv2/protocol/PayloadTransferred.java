package com.launchdarkly.sdk.internal.fdv2.protocol;

import com.launchdarkly.sdk.json.JsonSerializable;

/**
 * Represents a payload transfer notification.
 */
public final class PayloadTransferred implements JsonSerializable {
    private String state;
    private Integer version;

    /**
     * Default constructor for JSON deserialization.
     */
    public PayloadTransferred() {}

    /**
     * Constructs a PayloadTransferred with the specified properties.
     *
     * @param state the state identifier
     * @param version the version
     */
    public PayloadTransferred(String state, Integer version) {
        this.state = state;
        this.version = version;
    }

    /**
     * Returns the state identifier.
     *
     * @return the state identifier
     */
    public String getState() {
        return state;
    }

    /**
     * Sets the state identifier.
     *
     * @param state the state identifier
     */
    public void setState(String state) {
        this.state = state;
    }

    /**
     * Returns the version.
     *
     * @return the version
     */
    public Integer getVersion() {
        return version;
    }

    /**
     * Sets the version.
     *
     * @param version the version
     */
    public void setVersion(Integer version) {
        this.version = version;
    }

    /**
     * Validates that all required fields are present.
     *
     * @throws IllegalArgumentException if any required field is missing
     */
    public void validate() {
        if (state == null || version == null) {
            throw new IllegalArgumentException("Required field missing");
        }
    }
}

