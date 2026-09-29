package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.internal.http.FailureClass;

import org.junit.Test;

import java.time.Duration;
import java.util.Random;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit coverage for the {@link PollingStrategy} state machine. Uses ms-scale
 * numbers so tests are fast; the ratios match production's minute-scale
 * extended-regime targets.
 */
@SuppressWarnings("javadoc")
public class PollingStrategyTest {
  private static final Duration NORMAL = Duration.ofMillis(100);
  private static final Duration EXTENDED_INITIAL = Duration.ofMillis(500);

  private PollingStrategy strategy() {
    // Deterministic seed so jitter is reproducible in tests.
    return new PollingStrategy(NORMAL, EXTENDED_INITIAL, new Random(42L));
  }

  @Test
  public void freshStrategyReturnsNormalIntervalOnFirstWait() {
    PollingStrategy s = strategy();
    assertThat(s.nextWait(), equalTo(NORMAL));
    assertThat(s.getInitialDelay(), equalTo(NORMAL));
    assertThat(s.getMaxDelay(), equalTo(NORMAL));
  }

  @Test
  public void normalFailuresDoNotChangeInitialDelay() {
    PollingStrategy s = strategy();
    s.onFailure(FailureClass.NORMAL);
    s.onFailure(FailureClass.NORMAL);
    s.onFailure(FailureClass.NORMAL);
    // initialDelay stays at pollInterval; maxDelay stays at pollInterval;
    // so nextWait always == normalInterval.
    assertThat(s.getInitialDelay(), equalTo(NORMAL));
    assertThat(s.getMaxDelay(), equalTo(NORMAL));
    assertThat(s.nextWait(), equalTo(NORMAL));
  }

  @Test
  public void unexpectedFailureFromNormalRegimeTransitionsToExtended() {
    PollingStrategy s = strategy();
    s.onFailure(FailureClass.UNEXPECTED);
    // Transitioned: initialDelay swapped to extendedInitial; maxDelay to 1hr.
    assertThat(s.getInitialDelay(), equalTo(EXTENDED_INITIAL));
    assertThat(s.getMaxDelay(), equalTo(PollingStrategy.EXTENDED_MAX_DELAY));
    // First extended wait must equal extendedInitial (n reset to 1 → T = initial * 2^0).
    // Under jitter, actual wait is in [T/2, T].
    Duration w = s.nextWait();
    assertThat(w.toMillis(), lessThanOrEqualTo(EXTENDED_INITIAL.toMillis()));
    assertThat(w.toMillis(), greaterThanOrEqualTo(EXTENDED_INITIAL.toMillis() / 2));
  }

  @Test
  public void mixedClassificationNormalThenUnexpectedStartsAtExtendedInitial() {
    // Two normal failures advance n; then an unexpected transition should reset n to 1
    // and use extendedInitial directly rather than extendedInitial * 2^currentN.
    PollingStrategy s = strategy();
    s.onFailure(FailureClass.NORMAL);
    s.onFailure(FailureClass.NORMAL);
    // At this point still in normal regime; initialDelay unchanged.
    assertThat(s.getInitialDelay(), equalTo(NORMAL));

    s.onFailure(FailureClass.UNEXPECTED);
    assertThat(s.getInitialDelay(), equalTo(EXTENDED_INITIAL));
    Duration w = s.nextWait();
    // T = extendedInitial * 2^0 = extendedInitial. Not extendedInitial * 2^3.
    assertThat(w.toMillis(), lessThanOrEqualTo(EXTENDED_INITIAL.toMillis()));
    assertThat(w.toMillis(), greaterThanOrEqualTo(EXTENDED_INITIAL.toMillis() / 2));
  }

  @Test
  public void extendedRegimeProgressionClampsToMaxDelay() {
    // With extendedInitial = 500ms and max = 1hr, doubling progression is
    // 500ms, 1s, 2s, 4s, ... until clamped to 1hr.
    // We use a smaller max via a custom construction to exercise the clamp
    // quickly; see below.
    Duration extInitial = Duration.ofMillis(50);
    // Force max delay via a custom strategy. PollingStrategy.EXTENDED_MAX_DELAY
    // is the 1hr default; not overridable, so we validate clamp indirectly by
    // checking that many doublings never exceed max.
    PollingStrategy s = new PollingStrategy(NORMAL, extInitial, new Random(1L));
    s.onFailure(FailureClass.UNEXPECTED); // enter extended, n=1
    // Advance n many times; verify T never exceeds max.
    for (int i = 0; i < 40; i++) {
      Duration w = s.nextWait();
      // Wait is T-J; T <= max; so wait <= max.
      assertThat(w.compareTo(PollingStrategy.EXTENDED_MAX_DELAY) <= 0, equalTo(true));
      s.onFailure(FailureClass.NORMAL); // continue in extended regime, advance n
    }
  }

  @Test
  public void firstSuccessDoesNotResetExtendedRegime() {
    PollingStrategy s = strategy();
    s.onFailure(FailureClass.UNEXPECTED); // enter extended
    assertThat(s.getInitialDelay(), equalTo(EXTENDED_INITIAL));

    s.onSuccess(); // first success — sets flag but doesn't reset
    assertThat(s.getInitialDelay(), equalTo(EXTENDED_INITIAL));
    assertThat(s.getMaxDelay(), equalTo(PollingStrategy.EXTENDED_MAX_DELAY));
  }

  @Test
  public void twoConsecutiveSuccessesResetToNormalRegime() {
    PollingStrategy s = strategy();
    s.onFailure(FailureClass.UNEXPECTED);
    s.onSuccess();
    s.onSuccess();
    assertThat(s.getInitialDelay(), equalTo(NORMAL));
    assertThat(s.getMaxDelay(), equalTo(NORMAL));
    assertThat(s.getN(), equalTo(0));
  }

  @Test
  public void failureBetweenSuccessesClearsPriorSuccessFlag() {
    PollingStrategy s = strategy();
    s.onFailure(FailureClass.UNEXPECTED);
    s.onSuccess(); // prior=success
    s.onFailure(FailureClass.NORMAL); // clears prior=success
    // Now a single success alone should NOT reset.
    s.onSuccess();
    assertThat(s.getInitialDelay(), equalTo(EXTENDED_INITIAL));
  }

  @Test
  public void extendedInitialClampedToPollInterval() {
    // If extendedInitialInterval < pollInterval, effective floor is pollInterval.
    Duration longPoll = Duration.ofMillis(1000);
    Duration shortExt = Duration.ofMillis(200);
    PollingStrategy s = new PollingStrategy(longPoll, shortExt, new Random(0));
    s.onFailure(FailureClass.UNEXPECTED);
    assertThat(s.getInitialDelay(), equalTo(longPoll));
  }

  @Test
  public void onFailureReturnsTrueOnlyOnTransitionIntoExtended() {
    PollingStrategy s = new PollingStrategy(NORMAL, EXTENDED_INITIAL, new Random(0));
    assertFalse(s.onFailure(FailureClass.NORMAL));   // still in normal
    assertTrue(s.onFailure(FailureClass.UNEXPECTED)); // transition -> extended
    assertFalse(s.onFailure(FailureClass.UNEXPECTED)); // already in extended
    assertFalse(s.onFailure(FailureClass.NORMAL));    // still in extended
  }

  @Test
  public void nInExtendedDoublesEvenWhenPollIntervalEqualsExtendedInitial() {
    // Regression: an equality-based transition check (initialDelay == normalInterval)
    // would hold n at 1 whenever pollInterval >= extendedInitial, since the extended
    // clamp forces initialDelay back to normalInterval. The explicit inExtended flag
    // keeps n doubling.
    Duration equal = Duration.ofMillis(500);
    PollingStrategy s = new PollingStrategy(equal, equal, new Random(0));
    s.onFailure(FailureClass.UNEXPECTED); // enter extended, n=1
    assertThat(s.getN(), equalTo(1));
    s.onFailure(FailureClass.UNEXPECTED); // still in extended, n=2
    assertThat(s.getN(), equalTo(2));
    s.onFailure(FailureClass.UNEXPECTED); // still in extended, n=3
    assertThat(s.getN(), equalTo(3));
  }

  @Test
  public void twoConsecutiveSuccessesReArmExtendedTransition() {
    // After healthy-op reset, a subsequent UNEXPECTED failure should re-transition
    // into extended (onFailure returns true again). The inExtended flag must be
    // cleared by the reset.
    PollingStrategy s = new PollingStrategy(NORMAL, EXTENDED_INITIAL, new Random(0));
    assertTrue(s.onFailure(FailureClass.UNEXPECTED)); // -> extended
    s.onSuccess();
    s.onSuccess(); // two consecutive successes -> reset to normal
    assertTrue(s.onFailure(FailureClass.UNEXPECTED)); // re-transition -> extended
  }
}
