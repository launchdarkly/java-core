package com.launchdarkly.sdk.internal.fdv2.protocol;

import com.launchdarkly.sdk.json.JsonSerializable;

/**
 * Represents a delete operation for an object.
 */
public final class DeleteObject implements JsonSerializable {
    private String kind;
    private String key;
    private Integer version;

    /**
     * Default constructor for JSON deserialization.
     */
    public DeleteObject() {}

    /**
     * Constructs a DeleteObject with the specified properties.
     *
     * @param kind the kind of object
     * @param key the key of the object
     * @param version the version of the object
     */
    public DeleteObject(String kind, String key, Integer version) {
        this.kind = kind;
        this.key = key;
        this.version = version;
    }

    /**
     * Returns the kind of object.
     *
     * @return the kind of object
     */
    public String getKind() {
        return kind;
    }

    /**
     * Sets the kind of object.
     *
     * @param kind the kind of object
     */
    public void setKind(String kind) {
        this.kind = kind;
    }

    /**
     * Returns the key of the object.
     *
     * @return the key of the object
     */
    public String getKey() {
        return key;
    }

    /**
     * Sets the key of the object.
     *
     * @param key the key of the object
     */
    public void setKey(String key) {
        this.key = key;
    }

    /**
     * Returns the version of the object.
     *
     * @return the version of the object
     */
    public Integer getVersion() {
        return version;
    }

    /**
     * Sets the version of the object.
     *
     * @param version the version of the object
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
        if (kind == null || key == null || version == null) {
            throw new IllegalArgumentException("Required field missing");
        }
    }
}

