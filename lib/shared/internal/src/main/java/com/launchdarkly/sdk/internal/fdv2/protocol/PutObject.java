package com.launchdarkly.sdk.internal.fdv2.protocol;

import com.google.gson.JsonElement;
import com.launchdarkly.sdk.json.JsonSerializable;

/**
 * Represents a put operation for an object.
 */
public final class PutObject implements JsonSerializable {
    private String kind;
    private String key;
    private Integer version;
    private JsonElement object;

    /**
     * Default constructor for JSON deserialization.
     */
    public PutObject() {}

    /**
     * Constructs a PutObject with the specified properties.
     *
     * @param kind the kind of object
     * @param key the key of the object
     * @param version the version of the object
     * @param object the object data (parsed JSON element, lazily deserialized)
     */
    public PutObject(String kind, String key, Integer version, JsonElement object) {
        this.kind = kind;
        this.key = key;
        this.version = version;
        this.object = object;
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
     * Returns the object data.
     *
     * @return the object data (parsed JSON element, lazily deserialized)
     */
    public JsonElement getObject() {
        return object;
    }

    /**
     * Sets the object data.
     *
     * @param object the object data (parsed JSON element, lazily deserialized)
     */
    public void setObject(JsonElement object) {
        this.object = object;
    }

    /**
     * Validates that all required fields are present.
     *
     * @throws IllegalArgumentException if any required field is missing
     */
    public void validate() {
        if (kind == null || key == null || version == null || object == null) {
            throw new IllegalArgumentException("Required field missing");
        }
    }
}

