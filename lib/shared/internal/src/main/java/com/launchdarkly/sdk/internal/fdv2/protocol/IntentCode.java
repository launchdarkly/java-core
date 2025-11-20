package com.launchdarkly.sdk.internal.fdv2.protocol;

import com.google.gson.annotations.SerializedName;
import com.launchdarkly.sdk.json.JsonSerializable;

/**
 * Represents the intent code for a payload transfer.
 */
public enum IntentCode implements JsonSerializable {
    /**
     * Transfer full payload.
     */
    @SerializedName("xfer-full")
    XFER_FULL("xfer-full"),
    
    /**
     * Transfer changes only.
     */
    @SerializedName("xfer-changes")
    XFER_CHANGES("xfer-changes"),
    
    /**
     * No transfer intent.
     */
    @SerializedName("none")
    NONE("none");
    
    private final String value;
    
    IntentCode(String value) {
        this.value = value;
    }
    
    /**
     * Returns the string representation of the intent code.
     *
     * @return the string value
     */
    public String getValue() {
        return value;
    }
    
    /**
     * Returns the IntentCode for the given string value.
     *
     * @param value the string value
     * @return the corresponding IntentCode, or null if not found
     */
    public static IntentCode fromString(String value) {
        if (value == null) {
            return null;
        }
        for (IntentCode code : values()) {
            if (code.value.equals(value)) {
                return code;
            }
        }
        return null;
    }
}

