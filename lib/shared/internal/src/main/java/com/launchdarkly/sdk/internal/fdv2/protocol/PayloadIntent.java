package com.launchdarkly.sdk.internal.fdv2.protocol;

import com.launchdarkly.sdk.json.JsonSerializable;

/**
 * Represents a payload intent indicating what should be transferred.
 */
public final class PayloadIntent implements JsonSerializable {
    private String id;
    private int target;
    private IntentCode intentCode;
    private String reason;

    /**
     * Default constructor for JSON deserialization.
     */
    public PayloadIntent() {}

    /**
     * Constructs a PayloadIntent with the specified properties.
     *
     * @param id the payload identifier
     * @param target the target version
     * @param intentCode the intent code
     * @param reason the reason for the intent
     */
    public PayloadIntent(String id, int target, IntentCode intentCode, String reason) {
        this.id = id;
        this.target = target;
        this.intentCode = intentCode;
        this.reason = reason;
    }

    /**
     * Returns the payload identifier.
     *
     * @return the payload identifier
     */
    public String getId() {
        return id;
    }

    /**
     * Sets the payload identifier.
     *
     * @param id the payload identifier
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * Returns the target version.
     *
     * @return the target version
     */
    public int getTarget() {
        return target;
    }

    /**
     * Sets the target version.
     *
     * @param target the target version
     */
    public void setTarget(int target) {
        this.target = target;
    }

    /**
     * Returns the intent code.
     *
     * @return the intent code
     */
    public IntentCode getIntentCode() {
        return intentCode;
    }

    /**
     * Sets the intent code.
     *
     * @param intentCode the intent code
     */
    public void setIntentCode(IntentCode intentCode) {
        this.intentCode = intentCode;
    }

    /**
     * Returns the reason for the intent.
     *
     * @return the reason
     */
    public String getReason() {
        return reason;
    }

    /**
     * Sets the reason for the intent.
     *
     * @param reason the reason
     */
    public void setReason(String reason) {
        this.reason = reason;
    }

    /**
     * Validates that all required fields are present.
     *
     * @throws IllegalArgumentException if any required field is missing
     */
    public void validate() {
        if (intentCode == null) {
            throw new IllegalArgumentException("Required field missing");
        }
    }
}

