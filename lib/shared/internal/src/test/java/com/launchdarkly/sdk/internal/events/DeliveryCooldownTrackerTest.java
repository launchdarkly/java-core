package com.launchdarkly.sdk.internal.events;

import com.launchdarkly.sdk.internal.BaseTest;
import com.launchdarkly.sdk.internal.events.DeliveryCooldownTracker.WindowToken;

import org.junit.Test;

import static com.launchdarkly.sdk.internal.events.DeliveryCooldownTracker.COOLDOWN_INITIAL_WINDOW_MILLIS;
import static com.launchdarkly.sdk.internal.events.DeliveryCooldownTracker.COOLDOWN_MAX_WINDOW_MILLIS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("javadoc")
public class DeliveryCooldownTrackerTest extends BaseTest {
  static final class FakeNanoClock {
    private long nowNanos = 100_000_000_000L;

    synchronized long now() {
      return nowNanos;
    }

    synchronized void advanceMillis(long millis) {
      nowNanos += millis * 1_000_000L;
    }
  }

  private final FakeNanoClock clock = new FakeNanoClock();
  private final DeliveryCooldownTracker tracker = new DeliveryCooldownTracker(clock::now);

  // Records an unexpected failure and then advances the clock past the window it opened,
  // returning the window length.
  private long failPastWindow() {
    long windowMillis = tracker.recordUnexpectedFailure(tracker.beginDelivery());
    assertTrue(windowMillis > 0);
    clock.advanceMillis(windowMillis + 1000);
    return windowMillis;
  }

  @Test
  public void startsOutsideCooldown() {
    assertFalse(tracker.inCooldown());
    assertEquals(0, tracker.remainingMillis());
  }

  @Test
  public void windowDoublesPerFailedProbeUpToCeiling() {
    long[] expected = {
        5 * 60_000L, 10 * 60_000L, 20 * 60_000L, 40 * 60_000L,
        60 * 60_000L, 60 * 60_000L, // clamped at the ceiling
    };
    for (long want : expected) {
      assertEquals(want, failPastWindow());
    }
  }

  @Test
  public void inWindowResultFromInFlightDeliveryDoesNotLengthenWindow() {
    WindowToken preWindowToken = tracker.beginDelivery();
    assertEquals(COOLDOWN_INITIAL_WINDOW_MILLIS, tracker.recordUnexpectedFailure(preWindowToken));

    // A result arriving while the window is open is from a delivery that was already in flight;
    // its token is stale, so it does not extend the window or advance the doubling sequence.
    clock.advanceMillis(60_000);
    assertEquals(0, tracker.recordUnexpectedFailure(preWindowToken));
    assertEquals(COOLDOWN_INITIAL_WINDOW_MILLIS - 60_000, tracker.remainingMillis());

    // The next failed probe doubles once, to 10 minutes, not twice.
    clock.advanceMillis(COOLDOWN_INITIAL_WINDOW_MILLIS - 60_000 + 1000);
    assertEquals(2 * COOLDOWN_INITIAL_WINDOW_MILLIS, tracker.recordUnexpectedFailure(tracker.beginDelivery()));
  }

  @Test
  public void resetsAfterTwoConsecutiveSuccesses() {
    failPastWindow();
    tracker.recordSuccess(tracker.beginDelivery());
    tracker.recordSuccess(tracker.beginDelivery());

    // The retry state has reset, so a new unexpected failure starts over at the initial delay.
    assertEquals(COOLDOWN_INITIAL_WINDOW_MILLIS, tracker.recordUnexpectedFailure(tracker.beginDelivery()));
  }

  @Test
  public void singleSuccessDoesNotReset() {
    failPastWindow();
    tracker.recordSuccess(tracker.beginDelivery());

    assertEquals(2 * COOLDOWN_INITIAL_WINDOW_MILLIS, tracker.recordUnexpectedFailure(tracker.beginDelivery()));
  }

  @Test
  public void ordinaryFailureBreaksSuccessStreakWithoutOpeningWindow() {
    tracker.recordFailure(tracker.beginDelivery());
    assertFalse(tracker.inCooldown());

    failPastWindow();
    tracker.recordSuccess(tracker.beginDelivery());
    tracker.recordFailure(tracker.beginDelivery()); // the two successes around it are not consecutive
    tracker.recordSuccess(tracker.beginDelivery());

    assertEquals(2 * COOLDOWN_INITIAL_WINDOW_MILLIS, tracker.recordUnexpectedFailure(tracker.beginDelivery()));
  }

  @Test
  public void remainingMillisCountsDown() {
    assertEquals(COOLDOWN_INITIAL_WINDOW_MILLIS, tracker.recordUnexpectedFailure(tracker.beginDelivery()));

    clock.advanceMillis(2 * 60_000);
    assertEquals(COOLDOWN_INITIAL_WINDOW_MILLIS - 2 * 60_000, tracker.remainingMillis());
    assertTrue(tracker.inCooldown());

    clock.advanceMillis(3 * 60_000 + 1000);
    assertEquals(0, tracker.remainingMillis());
    assertFalse(tracker.inCooldown());
  }

  @Test
  public void reportsResumeOnlyOnFirstPostWindowSuccess() {
    assertFalse(tracker.recordSuccess(tracker.beginDelivery())); // no window has ever opened

    assertTrue(tracker.recordUnexpectedFailure(tracker.beginDelivery()) > 0);
    assertFalse(tracker.recordSuccess(tracker.beginDelivery())); // defensive: in-window success changes nothing

    clock.advanceMillis(COOLDOWN_INITIAL_WINDOW_MILLIS + 1000);
    assertTrue(tracker.recordSuccess(tracker.beginDelivery()));  // the probe ends the window
    assertFalse(tracker.recordSuccess(tracker.beginDelivery())); // only reported once
  }

  @Test
  public void noSpuriousResumeAfterRecoverableFailure() {
    failPastWindow();
    tracker.recordFailure(tracker.beginDelivery()); // the probe fails recoverably; the window is over

    // Much later, an ordinary success must not be reported as a cooldown resumption.
    clock.advanceMillis(1000 * 60_000);
    assertFalse(tracker.recordSuccess(tracker.beginDelivery()));
  }

  @Test
  public void boundaryRoundSiblingsAreStaleAfterProbeSuccess() {
    // Window opens; after it elapses, three deliveries are dispatched concurrently in the
    // boundary round, all sharing the same token.
    tracker.recordUnexpectedFailure(tracker.beginDelivery());
    clock.advanceMillis(COOLDOWN_INITIAL_WINDOW_MILLIS + 1000);
    WindowToken boundaryToken = tracker.beginDelivery();

    // The probe's success ends the round; the sibling success must not combine with it into a
    // reset, and the sibling failure must not open a window (the next round's failure does).
    assertTrue(tracker.recordSuccess(boundaryToken));
    assertFalse(tracker.recordSuccess(boundaryToken));
    assertEquals(0, tracker.recordUnexpectedFailure(boundaryToken));
    assertFalse(tracker.inCooldown());

    // The retry state was not reset (one credited success, not two), so the next failed delivery
    // opens a doubled window.
    assertEquals(2 * COOLDOWN_INITIAL_WINDOW_MILLIS, tracker.recordUnexpectedFailure(tracker.beginDelivery()));
  }

  @Test
  public void boundaryRoundSiblingsAreStaleAfterRecoverableProbeFailure() {
    tracker.recordUnexpectedFailure(tracker.beginDelivery());
    clock.advanceMillis(COOLDOWN_INITIAL_WINDOW_MILLIS + 1000);
    WindowToken boundaryToken = tracker.beginDelivery();

    // The probe fails recoverably, which also ends the boundary round: the two sibling successes
    // are stale and must not combine into a reset.
    tracker.recordFailure(boundaryToken);
    assertFalse(tracker.recordSuccess(boundaryToken));
    assertFalse(tracker.recordSuccess(boundaryToken));

    assertEquals(2 * COOLDOWN_INITIAL_WINDOW_MILLIS, tracker.recordUnexpectedFailure(tracker.beginDelivery()));
  }

  @Test
  public void staleOrdinaryFailureCannotCancelTheNextWindow() {
    // A delivery is in flight when a concurrent delivery's failure opens the window.
    WindowToken inFlight = tracker.beginDelivery();
    tracker.recordUnexpectedFailure(inFlight);
    assertTrue(tracker.inCooldown());

    // The window elapses and a probe is dispatched; then the straggler's recoverable failure
    // lands. It is stale: were it allowed to end the already-elapsed window, it would close the
    // boundary round and stale the probe, so the probe's failure would open no window at all.
    clock.advanceMillis(COOLDOWN_INITIAL_WINDOW_MILLIS + 1000);
    WindowToken probe = tracker.beginDelivery();
    tracker.recordFailure(inFlight);

    assertEquals(2 * COOLDOWN_INITIAL_WINDOW_MILLIS, tracker.recordUnexpectedFailure(probe));
    assertTrue(tracker.inCooldown());
  }

  @Test
  public void staleOrdinaryFailureDoesNotBreakSuccessStreak() {
    failPastWindow();
    assertTrue(tracker.recordSuccess(tracker.beginDelivery())); // probe success ends the window
    WindowToken staleToken = tracker.beginDelivery();
    tracker.recordUnexpectedFailure(tracker.beginDelivery());
    clock.advanceMillis(2 * COOLDOWN_INITIAL_WINDOW_MILLIS + 1000);
    assertTrue(tracker.recordSuccess(tracker.beginDelivery()));
    tracker.recordFailure(staleToken); // stale; must not reset the streak
    tracker.recordSuccess(tracker.beginDelivery()); // second consecutive success resets the ladder

    assertEquals(COOLDOWN_INITIAL_WINDOW_MILLIS, tracker.recordUnexpectedFailure(tracker.beginDelivery()));
  }

  @Test
  public void ordinaryFailureOutsideWindowDoesNotStaleConcurrentResults() {
    // During healthy operation (no window has ever opened), an ordinary failure must not close a
    // boundary round that does not exist: a concurrent unexpected failure in the same round must
    // still open a window.
    WindowToken roundToken = tracker.beginDelivery();
    tracker.recordFailure(roundToken);
    assertTrue(tracker.recordUnexpectedFailure(roundToken) > 0);
    assertTrue(tracker.inCooldown());
  }

  @Test
  public void nullTokenIsNeverCurrent() {
    // An unstamped payload must never pass for a live delivery, whether or not a window has
    // ever existed.
    assertFalse(tracker.recordSuccess(null));
    tracker.recordUnexpectedFailure(tracker.beginDelivery());
    clock.advanceMillis(COOLDOWN_INITIAL_WINDOW_MILLIS + 1000);
    assertFalse(tracker.recordSuccess(null));

    // The boundary round is still open for the real probe.
    assertTrue(tracker.recordSuccess(tracker.beginDelivery()));
  }

  @Test
  public void defaultClockOpensWindow() {
    // Production passes no clock; the default real-time clock path must work end to end.
    DeliveryCooldownTracker realTimeTracker = new DeliveryCooldownTracker(null);
    assertEquals(COOLDOWN_INITIAL_WINDOW_MILLIS, realTimeTracker.recordUnexpectedFailure(realTimeTracker.beginDelivery()));
    assertTrue(realTimeTracker.inCooldown());
    assertTrue(realTimeTracker.remainingMillis() > COOLDOWN_INITIAL_WINDOW_MILLIS - 60_000);
  }

  @Test
  public void windowBoundaryInstantIsOutsideCooldown() {
    long windowMillis = tracker.recordUnexpectedFailure(tracker.beginDelivery());
    clock.advanceMillis(windowMillis);
    assertFalse(tracker.inCooldown());
    assertEquals(0, tracker.remainingMillis());
  }

  @Test
  public void ladderIsClampedAtCeilingIndefinitely() {
    // Runs well past the shift guard's exponent bound: without the bound, the shift distance
    // wraps at 64 and the window would collapse back to the initial delay.
    for (int i = 0; i < 70; i++) {
      long expected = i < 4 ? COOLDOWN_INITIAL_WINDOW_MILLIS << i : COOLDOWN_MAX_WINDOW_MILLIS;
      long windowMillis = tracker.recordUnexpectedFailure(tracker.beginDelivery());
      assertEquals("window for failed probe #" + (i + 1), expected, windowMillis);
      clock.advanceMillis(windowMillis + 1000);
    }
  }

  @Test
  public void inWindowOutcomesWithCurrentTokenUpdateNothing() {
    // No production caller currently presents a current token for an in-window outcome (tokens
    // are issued atomically with the cooldown check), but the tracker's own contract is that no
    // such outcome may move delivery state.
    tracker.recordUnexpectedFailure(tracker.beginDelivery());
    long remainingBefore = tracker.remainingMillis();
    WindowToken inWindowToken = tracker.beginDelivery();

    assertFalse(tracker.recordSuccess(inWindowToken));
    tracker.recordFailure(inWindowToken);
    assertEquals(0, tracker.recordUnexpectedFailure(inWindowToken));
    assertEquals(remainingBefore, tracker.remainingMillis());

    // The state is untouched: the real probe still reports the resumption, and the ladder has
    // stepped exactly once.
    clock.advanceMillis(COOLDOWN_INITIAL_WINDOW_MILLIS + 1000);
    assertTrue(tracker.recordSuccess(tracker.beginDelivery()));
    tracker.recordSuccess(tracker.beginDelivery());
    assertEquals(COOLDOWN_INITIAL_WINDOW_MILLIS, tracker.recordUnexpectedFailure(tracker.beginDelivery()));
  }

  @Test
  public void discardWarningIsTakenOncePerWindow() {
    tracker.recordUnexpectedFailure(tracker.beginDelivery());
    assertTrue(tracker.takeDiscardWarning());
    assertFalse(tracker.takeDiscardWarning());

    // A new window gets its own notice.
    clock.advanceMillis(COOLDOWN_INITIAL_WINDOW_MILLIS + 1000);
    tracker.recordUnexpectedFailure(tracker.beginDelivery());
    assertTrue(tracker.takeDiscardWarning());
    assertFalse(tracker.takeDiscardWarning());
  }

  @Test
  public void inWindowFailureDoesNotRearmTheDiscardWarning() {
    // An in-window unexpected failure (an ungated diagnostic delivery) opens no window, so it
    // must not hand the current window a second prominent notice.
    tracker.recordUnexpectedFailure(tracker.beginDelivery());
    assertTrue(tracker.takeDiscardWarning());
    assertEquals(0, tracker.recordUnexpectedFailure(tracker.beginDelivery()));
    assertFalse(tracker.takeDiscardWarning());
  }

  @Test
  public void windowWithNoDiscardsStillArmsTheNextWindowsWarning() {
    tracker.recordUnexpectedFailure(tracker.beginDelivery());
    // Nothing is flushed during this window, so the notice is never taken.
    clock.advanceMillis(COOLDOWN_INITIAL_WINDOW_MILLIS + 1000);
    tracker.recordUnexpectedFailure(tracker.beginDelivery());
    assertTrue(tracker.takeDiscardWarning());
  }

  @Test
  public void gateIssuesTokenOnlyOutsideWindow() {
    assertNotNull(tracker.beginDeliveryUnlessInCooldown());

    tracker.recordUnexpectedFailure(tracker.beginDelivery());
    assertNull(tracker.beginDeliveryUnlessInCooldown());

    clock.advanceMillis(COOLDOWN_INITIAL_WINDOW_MILLIS + 1000);
    assertNotNull(tracker.beginDeliveryUnlessInCooldown());
  }
}
