package com.launchdarkly.testhelpers;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.launchdarkly.testhelpers.InternalHelpers.timeDesc;
import static com.launchdarkly.testhelpers.InternalHelpers.timeUnit;

/**
 * Helper methods and test assertions related to concurrent data structures.
 * 
 * @since 1.1.0
 */
public abstract class ConcurrentHelpers {
  /**
   * Asserts that a future is completed within the specified timeout.
   * 
   * @param <T> the future's value type
   * @param future the future
   * @param timeout the maximum time to wait
   * @param timeoutUnit the time unit for the timeout (null defaults to milliseconds)
   * @return the completed value
   * @throws AssertionError if the timeout expires
   */
  public static <T> T assertFutureIsCompleted(Future<T> future, long timeout, TimeUnit timeoutUnit) {
    try {
      return future.get(timeout, timeUnit(timeoutUnit));
    } catch (TimeoutException e) {
      throw new AssertionError("Future was not completed within " + timeDesc(timeout, timeoutUnit));
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
  
  /**
   * Equivalent to {@link #assertFutureIsCompleted(Future, long, TimeUnit)}, but as a Hamcrest matcher.
   * 
   * @param <T> the future's value type
   * @param timeout the maximum time to wait
   * @param timeoutUnit the time unit for the timeout (null defaults to milliseconds)
   * @return a matcher
   */
  public static <T> Matcher<Future<T>> isCompletedWithin(long timeout, TimeUnit timeoutUnit) {
    return new TypeSafeDiagnosingMatcher<Future<T>>() {
      @Override
      public void describeTo(Description description) {
        description.appendText("Future is completed within " + timeDesc(timeout, timeoutUnit));
      }

      @Override
      protected boolean matchesSafely(Future<T> item, Description mismatchDescription) {
        try {
          item.get(timeout, timeUnit(timeoutUnit));
          return true;
        } catch (TimeoutException e) {
          mismatchDescription.appendText("timed out");
          return false;
        } catch (ExecutionException e) {
          throw new RuntimeException(e);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    };
  }
  
  /**
   * Asserts that a future is completed within the specified timeout.
   *
   * @param <T> the future's value type
   * @param future the future
   * @param timeout the maximum time to wait
   * @param timeoutUnit the time unit for the timeout (null defaults to milliseconds)
   * @throws AssertionError if the future is completed
   */
  public static <T> void assertFutureIsNotCompleted(Future<T> future, long timeout, TimeUnit timeoutUnit) {
    try {
      T value = future.get(timeout, timeUnit(timeoutUnit));
      throw new AssertionError("Future was unexpectedly completed with value: " + value);
    } catch (TimeoutException e) {
      return;
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Equivalent to {@link #assertFutureIsNotCompleted(Future, long, TimeUnit)}, but as a Hamcrest matcher.
   * 
   * @param <T> the future's value type
   * @param timeout the maximum time to wait
   * @param timeoutUnit the time unit for the timeout (null defaults to milliseconds)
   * @return a matcher
   */
  public static <T> Matcher<Future<T>> isNotCompletedWithin(long timeout, TimeUnit timeoutUnit) {
    return new TypeSafeDiagnosingMatcher<Future<T>>() {
      @Override
      public void describeTo(Description description) {
        description.appendText("Future is not completed within " + timeDesc(timeout, timeoutUnit));
      }

      @Override
      protected boolean matchesSafely(Future<T> item, Description mismatchDescription) {
        try {
          T value = item.get(timeout, timeUnit(timeoutUnit));
          mismatchDescription.appendText("unexpectedly completed with value: " + value);
          return false;
        } catch (TimeoutException e) {
          return true;
        } catch (ExecutionException e) {
          throw new RuntimeException(e);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    };
  }
  
  /**
   * Waits for a value to be available from a {@code BlockingQueue} and consumes the value.
   * 
   * @param <T> the value type
   * @param values the queue
   * @param timeout the maximum time to wait
   * @param timeoutUnit the time unit for the timeout (null defaults to milliseconds)
   * @return the value obtained from the queue
   * @throws AssertionError if the timeout expires
   */
  public static <T> T awaitValue(BlockingQueue<T> values, long timeout, TimeUnit timeoutUnit) {
    try {
      T value = values.poll(timeout, timeUnit(timeoutUnit));
      if (value == null) {
        throw new AssertionError("did not receive a value within " + timeDesc(timeout, timeoutUnit));
      }
      return value;
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Asserts that no values are available fro a queue within the specified timeout.
   * 
   * @param <T> the value type
   * @param values the queue
   * @param timeout the maximum time to wait
   * @param timeoutUnit the time unit for the timeout (null defaults to milliseconds)
   * @throws AssertionError if a value was available from the queue
   */
  public static <T> void assertNoMoreValues(BlockingQueue<T> values, long timeout, TimeUnit timeoutUnit) {
    try {
      T value = values.poll(timeout, timeUnit(timeoutUnit));
      if (value != null) {
        throw new AssertionError("expected no more values, but received: " + value);
      }
    } catch (InterruptedException e) {}
  }
  
  /**
   * Shortcut for calling {@code Thread.sleep()} when an {@code InterruptedException} is not
   * expected, so you do not have to catch it.
   * 
   * @param delay the length of time to wait
   * @param delayUnit the time unit for the delay (null defaults to milliseconds)
   * @throws RuntimeException if an {@code InterruptedException} unexpectedly happened
   */
  public static void trySleep(long delay, TimeUnit delayUnit) {
    try {
      Thread.sleep(timeUnit(delayUnit).toMillis(delay));
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
