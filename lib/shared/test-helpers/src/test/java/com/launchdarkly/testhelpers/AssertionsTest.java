package com.launchdarkly.testhelpers;

import org.junit.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

@SuppressWarnings("javadoc")
public class AssertionsTest {
  @Test
  public void assertPolledFunctionReturnsValueSuccessOnFirstTry() {
    String value = Assertions.assertPolledFunctionReturnsValue(
        1, TimeUnit.SECONDS,
        10, TimeUnit.MILLISECONDS,
        () -> "yes"
        );
    assertThat(value, equalTo("yes"));
  }
  
  @Test
  public void assertPolledFunctionReturnsValueSuccessOnLaterTry() {
    AtomicInteger i = new AtomicInteger(0);
    String value = Assertions.assertPolledFunctionReturnsValue(
        200, TimeUnit.MILLISECONDS,
        10, TimeUnit.MILLISECONDS,
        () -> {
          return i.incrementAndGet() >= 5 ? "yes" : null;
        });
    assertThat(value, equalTo("yes"));
  }

  @Test
  public void assertPolledFunctionReturnsValueFailure() {
    AtomicInteger i = new AtomicInteger(0);
    requireAssertionError(() -> {
      Assertions.assertPolledFunctionReturnsValue(
          200, TimeUnit.MILLISECONDS,
          10, TimeUnit.MILLISECONDS,
          () -> {
            i.incrementAndGet();
            return null;
          });
    });
    assertThat(i.get(), greaterThan(1));
  }
  
  public static String requireAssertionError(Runnable action) {
    try {
      action.run();
      throw new AssertionError("expected AssertionError, did not get one");
    } catch (AssertionError e) {
      return e.getMessage();
    }
  }
}
