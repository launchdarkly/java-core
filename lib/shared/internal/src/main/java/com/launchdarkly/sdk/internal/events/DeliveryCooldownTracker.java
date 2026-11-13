package com.launchdarkly.sdk.internal.events;

/**
 * Decides when analytics event delivery is in a cooldown window after an unexpected failure, so
 * that a condition such as an invalid SDK key backs delivery off instead of permanently disabling
 * it.
 * <p>
 * Every delivery outcome is reported together with the {@link WindowToken} issued when that
 * delivery was dispatched. A token stops being current when a window opens or ends, which confines
 * window and ladder movement to actual probes even with concurrent deliveries straddling a window
 * boundary. A stale outcome can still do one thing: a failure breaks the consecutive-success
 * streak regardless of which round dispatched it, since a real failure makes the successes around
 * it non-consecutive. A stale success, by contrast, counts for nothing.
 * <p>
 * The windows are not floored at the flush interval: delivery is attempted only when a flush
 * occurs (scheduled, manual, or at shutdown), and in-window flushes of any kind make no attempt,
 * so a window shorter than the flush interval cannot make the processor try faster while failing
 * than while healthy.
 * <p>
 * The tracker is pure state: it starts no tasks and does no I/O or logging. Deliveries are
 * dispatched from the main dispatcher thread while their outcomes arrive on delivery worker
 * threads, so all methods are synchronized; callers must not do anything slow inside a pattern
 * that would extend the lock's reach.
 */
final class DeliveryCooldownTracker {
  static final long COOLDOWN_INITIAL_WINDOW_MILLIS = 5 * 60_000L;
  static final long COOLDOWN_MAX_WINDOW_MILLIS = 60 * 60_000L;
  static final int COOLDOWN_RESET_THRESHOLD = 2;

  private static final long NANOS_PER_MILLI = 1_000_000L;

  /**
   * The tracker's time source. This stands in for {@code java.util.function.LongSupplier}, which
   * cannot be used because this module must run on Android below API 24, where
   * {@code java.util.function} does not exist.
   */
  interface NanoTimeSource {
    /**
     * Returns a reading with {@link System#nanoTime()} semantics: a monotonic nanosecond value
     * whose absolute magnitude is meaningless and may wrap.
     *
     * @return the reading
     */
    long nanoTime();
  }

  /**
   * Identifies the span between cooldown-window boundaries in which a delivery was dispatched; a
   * token stops being current when a window opens or ends. The dispatcher obtains one from
   * {@link #beginDelivery()} when it hands a payload off for delivery and presents it with the
   * delivery's outcome; the tracker ignores outcomes whose token is no longer current. A null
   * token is never current, so an unstamped payload cannot pass for a live delivery.
   */
  static final class WindowToken {
    private final long generation;

    private WindowToken(long generation) {
      this.generation = generation;
    }
  }

  private final NanoTimeSource nanoTimeFn;
  private int attempts = 0;
  private int consecutiveSuccesses = 0;
  private Long windowEndNanos = null;
  private long epoch = 0;
  private boolean warnedThisWindow = false;

  /**
   * Creates an instance.
   *
   * @param nanoTimeFn the time source; null to use {@code System.nanoTime()}. Only tests pass a
   *   non-null value.
   */
  DeliveryCooldownTracker(NanoTimeSource nanoTimeFn) {
    this.nanoTimeFn = nanoTimeFn == null ? System::nanoTime : nanoTimeFn;
  }

  /**
   * Returns true if a cooldown window is in effect.
   *
   * @return true if delivery is cooling down
   */
  synchronized boolean inCooldown() {
    // The subtraction-based comparison stays correct when nanoTime wraps.
    return windowEndNanos != null && nanoTimeFn.nanoTime() - windowEndNanos < 0;
  }

  /**
   * Returns the token that a delivery dispatched now must present with its outcome.
   *
   * @return the token
   */
  synchronized WindowToken beginDelivery() {
    return new WindowToken(epoch + 1);
  }

  /**
   * Returns the token for a delivery dispatched now, or null if a cooldown window is in effect.
   * The check and the token issue are one atomic step, so a window cannot open in between: a
   * delivery either sees the window, or carries a token that the window's opening made stale.
   *
   * @return the token, or null if delivery is cooling down
   */
  synchronized WindowToken beginDeliveryUnlessInCooldown() {
    return inCooldown() ? null : beginDelivery();
  }

  /**
   * Returns true at most once per window, for the caller to make the first discard of each window
   * more prominent than the rest. The check-and-set runs under the same lock that opens and arms
   * windows.
   *
   * @return true if this is the first time this is called for the current window
   */
  synchronized boolean takeDiscardWarning() {
    if (warnedThisWindow) {
      return false;
    }
    warnedThisWindow = true;
    return true;
  }

  /**
   * Returns how much of the current window is left, or zero outside a window.
   *
   * @return the remaining window length in milliseconds
   */
  synchronized long remainingMillis() {
    if (windowEndNanos == null) {
      return 0;
    }
    long remainingNanos = windowEndNanos - nanoTimeFn.nanoTime();
    return remainingNanos > 0 ? remainingNanos / NANOS_PER_MILLI : 0;
  }

  /**
   * Records a successful delivery. The retry state resets only after
   * {@link #COOLDOWN_RESET_THRESHOLD} consecutive successes. Stale outcomes, and outcomes
   * arriving while a window is open, update nothing.
   *
   * @param token the token issued when this delivery was dispatched
   * @return true when this success is the probe that ended a window (the first success after the
   *   window elapsed), so the caller can log the resumption
   */
  synchronized boolean recordSuccess(WindowToken token) {
    if (!isCurrent(token)) {
      return false;
    }
    if (inCooldown()) {
      return false; // defensive: a current token cannot originate inside a window
    }
    boolean resumed = false;
    if (windowEndNanos != null) {
      windowEndNanos = null;
      resumed = true;
      // The probe ends the window: the tokens previously given out go stale.
      epoch++;
    }
    consecutiveSuccesses++;
    if (consecutiveSuccesses >= COOLDOWN_RESET_THRESHOLD) {
      attempts = 0;
    }
    return resumed;
  }

  /**
   * Records a delivery failure that was not unexpected. It breaks the consecutive-success streak,
   * even on a stale token, but never opens or lengthens a window. Unstamped outcomes update
   * nothing.
   *
   * @param token the token issued when this delivery was dispatched
   */
  synchronized void recordFailure(WindowToken token) {
    if (token == null) {
      return; // unstamped: not the outcome of a real delivery
    }
    // A real failure breaks the streak regardless of its era: the reset threshold counts
    // consecutive successful deliveries, and a failure between two successes makes them
    // non-consecutive no matter which round dispatched it.
    consecutiveSuccesses = 0;
    if (!isCurrent(token)) {
      return;
    }
    if (inCooldown()) {
      return; // defensive: a current token cannot originate inside a window
    }
    if (windowEndNanos != null) {
      // A probe that fails recoverably still ends the window: clear it so a later success is not
      // misreported as the resumption.
      windowEndNanos = null;
      epoch++;
    }
  }

  /**
   * Records a delivery failure classified as unexpected. It opens a window that doubles from
   * {@link #COOLDOWN_INITIAL_WINDOW_MILLIS} up to {@link #COOLDOWN_MAX_WINDOW_MILLIS} per failed
   * probe. A stale token still breaks the consecutive-success streak but never opens or steps a
   * window, so one incident's concurrent failures step the ladder only once. Unstamped outcomes
   * update nothing.
   *
   * @param token the token issued when this delivery was dispatched
   * @return the length in milliseconds of the newly opened window, or zero if no window was opened
   */
  synchronized long recordUnexpectedFailure(WindowToken token) {
    if (token == null) {
      return 0; // unstamped: not the outcome of a real delivery
    }
    // See recordFailure: a real failure breaks the streak regardless of its era.
    consecutiveSuccesses = 0;
    if (!isCurrent(token)) {
      return 0;
    }
    if (inCooldown()) {
      return 0; // defensive: a current token cannot originate inside a window
    }
    attempts++;
    epoch++;
    warnedThisWindow = false;
    long windowMillis = COOLDOWN_MAX_WINDOW_MILLIS;
    // Compare against the ceiling scaled down rather than the base scaled up, so the shift cannot
    // overflow. (A shift of 63 or more would always be clamped anyway.)
    int exponent = attempts - 1;
    if (exponent >= 0 && exponent < 63
        && COOLDOWN_INITIAL_WINDOW_MILLIS <= (COOLDOWN_MAX_WINDOW_MILLIS >> exponent)) {
      windowMillis = COOLDOWN_INITIAL_WINDOW_MILLIS << exponent;
    }
    windowEndNanos = nanoTimeFn.nanoTime() + windowMillis * NANOS_PER_MILLI;
    return windowMillis;
  }

  private boolean isCurrent(WindowToken token) {
    return token != null && token.generation == epoch + 1;
  }
}
