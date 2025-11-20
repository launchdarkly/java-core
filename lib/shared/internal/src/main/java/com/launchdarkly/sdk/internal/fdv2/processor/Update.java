package com.launchdarkly.sdk.internal.fdv2.processor;

import com.google.gson.JsonElement;

/**
 * Represents information for one keyed object.
 */
public final class Update {
    private String kind;
    private String key;
    private int version;
    private JsonElement object;
    private Boolean deleted;

    /**
     * Default constructor.
     */
    public Update() {}

    /**
     * Constructs an Update with the specified properties.
     *
     * @param kind the kind of object
     * @param key the key of the object
     * @param version the version of the object
     * @param object the object data (optional, parsed JSON element, lazily deserialized)
     * @param deleted whether the object is deleted (optional)
     */
    public Update(String kind, String key, int version, JsonElement object, Boolean deleted) {
        this.kind = kind;
        this.key = key;
        this.version = version;
        this.object = object;
        this.deleted = deleted;
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
    public int getVersion() {
        return version;
    }

    /**
     * Sets the version of the object.
     *
     * @param version the version of the object
     */
    public void setVersion(int version) {
        this.version = version;
    }

    /**
     * Returns the object data.
     *
     * @return the object data, or null if not present (parsed JSON element, lazily deserialized)
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
     * Returns whether the object is deleted.
     *
     * @return true if the object is deleted, false if not deleted, null if not specified
     */
    public Boolean getDeleted() {
        return deleted;
    }

    /**
     * Sets whether the object is deleted.
     *
     * @param deleted true if the object is deleted, false if not deleted, null if not specified
     */
    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }

    /**
     * Returns whether the object is deleted (convenience method).
     *
     * @return true if deleted is set and true, false otherwise
     */
    public boolean isDeleted() {
        return deleted != null && deleted;
    }
}

