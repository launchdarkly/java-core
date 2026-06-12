package com.launchdarkly.sdk.json;

import java.io.Serializable;

/**
 * Marker interface for SDK classes that have a custom JSON serialization.
 * 
 * @see JsonSerialization
 * @see LDGson
 */
public interface JsonSerializable extends Serializable {
}
