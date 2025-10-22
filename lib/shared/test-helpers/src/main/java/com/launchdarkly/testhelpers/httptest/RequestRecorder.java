package com.launchdarkly.testhelpers.httptest;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An object that records all requests.
 * <p>
 * Normally you won't need to use this class directly, because {@link HttpServer} has a
 * built-in instance that captures all requests. You can use it if you need to capture
 * only a subset of requests.
 */
public final class RequestRecorder implements Handler {
  /**
   * The default timeout for {@link #requireRequest()}: 5 seconds.
   */
  public static final int DEFAULT_TIMEOUT_MILLIS = 5000;
  
  private final BlockingQueue<RequestInfo> requests = new LinkedBlockingQueue<>();
  private final AtomicBoolean enabled = new AtomicBoolean(true);
  
  @Override
  public void apply(RequestContext context) {
    if (enabled.get()) {
      requests.add(context.getRequest());
    }
  }

  /**
   * The number of requests currently in the queue.
   * 
   * @return the number of stored requests that have not been consumed
   */
  public int count() {
    return requests.size();
  }

  /**
   * Returns true if the recorder is capturing requests. This is true by default.
   * 
   * @return true if enabled
   */
  public boolean isEnabled() {
    return enabled.get();
  }
  
  /**
   * Sets whether the recorder should capture requests. This is true by default.
   * 
   * @param enabled true to enable the recorder, false to disable
   */
  public void setEnabled(boolean enabled) {
    this.enabled.set(enabled);
  }
  
  /**
   * Consumes and returns the first request in the queue, blocking until one is available,
   * using {@link #DEFAULT_TIMEOUT_MILLIS}.
   * 
   * @return the request information
   * @throws IllegalStateException if the timeout expires
   */
  public RequestInfo requireRequest() {
    return requireRequest(DEFAULT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
  }
  
  /**
   * Consumes and returns the first request in the queue, blocking until one is available.
   * 
   * @param timeout the maximum length of time to wait
   * @param timeoutUnit the time unit for the timeout
   * @return the request information
   * @throws RuntimeException if the timeout expires
   */
  public RequestInfo requireRequest(long timeout, TimeUnit timeoutUnit) {
    try {
      RequestInfo ret = requests.poll(timeout, timeoutUnit == null ? TimeUnit.MILLISECONDS : timeoutUnit);
      if (ret == null) {
        throw new IllegalStateException(new TimeoutException());
      }
      return ret;
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
  
  /**
   * Asserts that there are no requests in the queue and none are received within
   * the specified timeout.
   * 
   * @param timeout the maximum length of time to wait
   * @param timeoutUnit the time unit for the timeout
   * @throws IllegalStateException if a request was received
   */
  public void requireNoRequests(long timeout, TimeUnit timeoutUnit) {
    try {
      RequestInfo ret = requests.poll(timeout, timeoutUnit == null ? TimeUnit.MILLISECONDS : timeoutUnit);
      if (ret != null) {
         throw new IllegalStateException("received an unexpected request");
      }
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
