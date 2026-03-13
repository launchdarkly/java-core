package com.launchdarkly.sdk;

/**
 * An interface which can dynamically provide attribute values.
 */
public interface AttributeProvider { 
  /**
   * Provides a value for rule evaluation.
   * @param key the name of the value
   * @return a value, or null indicating the provider is not able to provide a value
   */
  LDValue getValue(String key);

  /**
   * Provides keys for logging and event collection. These keys will be used to call {@link #getValue}.
   * @return all of the keys which this implementation can provide
   */
  Iterable<String> getKeys();
}