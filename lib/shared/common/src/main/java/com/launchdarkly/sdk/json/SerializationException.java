package com.launchdarkly.sdk.json;

/**
 * General exception class for all errors in serializing or deserializing JSON.
 * <p>
 * The SDK uses this class to avoid depending on exception types from the underlying JSON framework
 * that it uses. The underlying exception can be inspected with the {@link Exception#getCause()}
 * method, but application code should not rely on those details since they are subject to change.
 */
@SuppressWarnings("serial")
public class SerializationException extends Exception {
  /**
   * Creates an instance.
   * @param cause the underlying exception
   */
  public SerializationException(Throwable cause) {
    super(cause);
  }
  
  /**
   * Creates an instance.
   * @param message a description of the error
   */
  public SerializationException(String message) {
    super(message);
  }
}
