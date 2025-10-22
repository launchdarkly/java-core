package com.launchdarkly.testhelpers;

import org.junit.Test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.launchdarkly.testhelpers.AssertionsTest.requireAssertionError;
import static com.launchdarkly.testhelpers.ConcurrentHelpers.assertFutureIsCompleted;
import static com.launchdarkly.testhelpers.ConcurrentHelpers.assertNoMoreValues;
import static com.launchdarkly.testhelpers.ConcurrentHelpers.awaitValue;
import static com.launchdarkly.testhelpers.ConcurrentHelpers.isCompletedWithin;
import static com.launchdarkly.testhelpers.ConcurrentHelpers.trySleep;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@SuppressWarnings("javadoc")
public class ConcurrentHelpersTest {
  @Test
  public void awaitValueSuccess() {
    BlockingQueue<String> q = new LinkedBlockingQueue<>();
    new Thread(() -> {
      q.add("a");
    }).start();
    String value = awaitValue(q, 1, TimeUnit.SECONDS);
    assertThat(value, equalTo("a"));
  }
  
  @Test
  public void awaitValueFailure() {
    BlockingQueue<String> q = new LinkedBlockingQueue<>();
    String message = requireAssertionError(() -> {
      awaitValue(q, 100, TimeUnit.MILLISECONDS);
    });
    assertThat(message, equalTo("did not receive a value within 100 milliseconds"));
  }

  @Test
  public void assertNoMoreValuesSuccess() {
    BlockingQueue<String> q = new LinkedBlockingQueue<>();
    assertNoMoreValues(q, 50, TimeUnit.MILLISECONDS);
  }

  @Test
  public void assertNoMoreValuesFailure() {
    BlockingQueue<String> q = new LinkedBlockingQueue<>();
    new Thread(() -> {
      trySleep(10, TimeUnit.MILLISECONDS);
      q.add("a");
    }).start();
    String message = requireAssertionError(() -> {
      assertNoMoreValues(q, 100, TimeUnit.MILLISECONDS);
    });
    assertThat(message, equalTo("expected no more values, but received: a"));
  }
  
  @Test
  public void assertFutureIsCompletedSuccess() {
    CompletableFuture<String> f = new CompletableFuture<String>();
    new Thread(() -> {
      f.complete("a");
    }).start();
    String value = assertFutureIsCompleted(f, 1, TimeUnit.SECONDS);
    assertThat(value, equalTo("a"));
  }

  @Test
  public void assertFutureIsCompletedFailure() {
    CompletableFuture<String> f = new CompletableFuture<String>();
    String message = requireAssertionError(() -> {
      assertFutureIsCompleted(f, 50, TimeUnit.MILLISECONDS);
    });
    assertThat(message, equalTo("Future was not completed within 50 milliseconds"));
  }
  
  @Test
  public void futureIsCompletedMatcherSuccess() {
    CompletableFuture<String> f = new CompletableFuture<String>();
    new Thread(() -> {
      f.complete("a");
    }).start();
    assertThat(f, isCompletedWithin(1, TimeUnit.SECONDS));
  }
  
  @Test
  public void futureIsCompletedMatcherFailure() {
    CompletableFuture<String> f = new CompletableFuture<String>();
    String message = requireAssertionError(() -> {
      assertThat(f, isCompletedWithin(50, TimeUnit.MILLISECONDS));
    });
    assertThat(message, containsString("Expected: Future is completed within 50 milliseconds"));
    assertThat(message, containsString("but: timed out"));
  }
}
