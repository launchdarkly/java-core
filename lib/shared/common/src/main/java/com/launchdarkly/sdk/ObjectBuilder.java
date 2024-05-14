package com.launchdarkly.sdk;

import java.util.HashMap;
import java.util.Map;

/**
 * A builder created by {@link LDValue#buildObject()}.
 * <p>
 * Builder methods are not thread-safe.
 */
public final class ObjectBuilder {
  // Note that we're not using ImmutableMap here because we don't want to duplicate its semantics
  // for duplicate keys (rather than overwriting the key *or* throwing an exception when you add it,
  // it accepts it but then throws an exception when you call build()). So we have to reimplement
  // the copy-on-write behavior. 
  private volatile Map<String, LDValue> builder = new HashMap<String, LDValue>();
  private volatile boolean copyOnWrite = false;
  
  /**
   * Sets a key-value pair in the builder, overwriting any previous value for that key.
   * @param key a string key
   * @param value a value
   * @return the same builder
   */
  public ObjectBuilder put(String key, LDValue value) {
    if (copyOnWrite) {
      builder = new HashMap<>(builder);
      copyOnWrite = false;
    }
    builder.put(key, value == null ? LDValue.ofNull() : value);
    return this;
  }

  /**
   * Sets a key-value pair in the builder, overwriting any previous value for that key.
   * @param key a string key
   * @param value a value
   * @return the same builder
   */
  public ObjectBuilder put(String key, boolean value) {
    return put(key, LDValue.of(value));
  }

  /**
   * Sets a key-value pair in the builder, overwriting any previous value for that key.
   * @param key a string key
   * @param value a value
   * @return the same builder
   */
  public ObjectBuilder put(String key, int value) {
    return put(key, LDValue.of(value));
  }

  /**
   * Sets a key-value pair in the builder, overwriting any previous value for that key.
   * @param key a string key
   * @param value a value
   * @return the same builder
   */
  public ObjectBuilder put(String key, long value) {
    return put(key, LDValue.of(value));
  }

  /**
   * Sets a key-value pair in the builder, overwriting any previous value for that key.
   * @param key a string key
   * @param value a value
   * @return the same builder
   */
  public ObjectBuilder put(String key, float value) {
    return put(key, LDValue.of(value));
  }

  /**
   * Sets a key-value pair in the builder, overwriting any previous value for that key.
   * @param key a string key
   * @param value a value
   * @return the same builder
   */
  public ObjectBuilder put(String key, double value) {
    return put(key, LDValue.of(value));
  }

  /**
   * Sets a key-value pair in the builder, overwriting any previous value for that key.
   * @param key a string key
   * @param value a value
   * @return the same builder
   */
  public ObjectBuilder put(String key, String value) {
    return put(key, LDValue.of(value));
  }

  /**
   * Returns an object containing the builder's current elements. Subsequent changes to the builder
   * will not affect this value (it uses copy-on-write logic, so the previous values will only be
   * copied to a new map if you continue to add elements after calling {@link #build()}.
   * @return an {@link LDValue} that is a JSON object
   */
  public LDValue build() {
    copyOnWrite = true;
    return LDValueObject.fromMap(builder); 
  }
}
