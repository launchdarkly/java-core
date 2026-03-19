package com.launchdarkly.testhelpers;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.launchdarkly.testhelpers.InternalHelpers.timeUnit;

/**
 * General test assertions that may be helpful in unit tests.
 * 
 * For more specific categories of assertions, see {@link ConcurrentHelpers},
 * {@link JsonAssertions}, and {@link TypeBehavior}.
 * 
 * @since 1.1.0
 */
public abstract class Assertions {
  /**
   * Repeatedly calls a function until it returns a non-null value or until a timeout elapses,
   * whichever comes first.
   * 
   * @param <T> the return type
   * @param timeout maximum time to wait
   * @param timeoutUnit time unit for timeout (null defaults to milliseconds)
   * @param interval how often to call the function
   * @param intervalUnit time unit for interval (null defaults to milliseconds)
   * @param fn the function to call
   * @return the function's return value
   * @throws AssertionError if the function did not return a non-null value before the timeout
   */
  public static <T> T assertPolledFunctionReturnsValue(
      long timeout,
      TimeUnit timeoutUnit,
      long interval,
      TimeUnit intervalUnit,
      Supplier<T> fn
      ) {
    long deadline = System.currentTimeMillis() + timeUnit(timeoutUnit).toMillis(timeout);
    while (System.currentTimeMillis() < deadline) {
      T result = fn.get();
      if (result != null) {
        return result;
      }
      try {
        Thread.sleep(timeUnit(intervalUnit).toMillis(interval));
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
    throw new AssertionError("timed out after " + timeout);
  }
}
