package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.internal.http.FailureClass;

import java.time.Duration;
import java.util.Random;

/**
 * Retry-timing state machine for the polling data source. Selects a per-attempt
 * delay based on prior outcomes:
 * <ul>
 *   <li>Normal regime: successive attempts wait {@code pollInterval}. No backoff
 *       is applied because {@code initialDelay} and {@code maxDelay} both equal
 *       {@code pollInterval}.</li>
 *   <li>Extended regime: entered on an {@link FailureClass#UNEXPECTED} failure.
 *       Waits start at {@code extendedInitialInterval} (floored at
 *       {@code pollInterval}) and double each attempt, clamped to
 *       {@link #EXTENDED_MAX_DELAY}.</li>
 *   <li>Healthy-op reset: two consecutive successful polls return the strategy
 *       to the normal regime.</li>
 * </ul>
 * <p>
 * The formula input {@code n} in {@code T = initialDelay * 2^(n-1)} resets to
 * zero whenever the delay bounds change (regime transition), so the first
 * attempt in the new regime uses the new initial delay directly.
 * <p>
 * All state is owned by the polling loop's own thread (currently the shared
 * ScheduledExecutorService in {@link PollingProcessor}). No external synchronization
 * is required as long as this invariant holds.
 */
final class PollingStrategy {
  static final Duration EXTENDED_MAX_DELAY = Duration.ofHours(1);

  private final Duration normalInterval;
  private final Duration extendedInitialInterval;
  private final Random rng;

  private int n;
  private boolean priorPollWasSuccessful;
  private boolean inExtended;
  private Duration initialDelay;
  private Duration maxDelay;

  PollingStrategy(Duration normalInterval, Duration extendedInitialInterval) {
    this(normalInterval, extendedInitialInterval, new Random());
  }

  // Visible for testing; deterministic seed injectable so jitter is reproducible.
  PollingStrategy(Duration normalInterval, Duration extendedInitialInterval, Random rng) {
    this.normalInterval = normalInterval;
    this.extendedInitialInterval = extendedInitialInterval;
    this.rng = rng;
    // Normal regime at construction: both initialDelay and maxDelay equal the
    // customer-configured pollInterval (there's no backoff in the normal
    // regime — successive normal-failure retries stay at pollInterval).
    this.initialDelay = normalInterval;
    this.maxDelay = normalInterval;
  }

  /**
   * Advance state after a poll failure. Returns {@code true} exactly once per
   * transition from the normal regime into the extended regime; the caller
   * can use the return value to emit an operator-visible log at the moment of
   * transition without re-firing on every subsequent UNEXPECTED failure while
   * already in extended regime.
   * <p>
   * On the transition, set {@code n = 1} and swap in the extended bounds, so
   * the first extended wait uses {@code extendedInitialInterval} directly
   * (the "reset n when delays change" invariant). On any other failure,
   * increment n so the delay doubles.
   * <p>
   * Extended-regime bounds are floored at the customer-configured
   * {@code pollInterval} — the wait never drops below that.
   */
  boolean onFailure(FailureClass failureClass) {
    this.priorPollWasSuccessful = false;
    if (failureClass == FailureClass.UNEXPECTED && !this.inExtended) {
      this.inExtended = true;
      this.n = 1;
      this.initialDelay = max(extendedInitialInterval, normalInterval);
      this.maxDelay = max(EXTENDED_MAX_DELAY, normalInterval);
      return true;
    }
    this.n++;
    return false;
  }

  /**
   * Advance state after a poll success. After two successes in a row, n resets
   * to zero and delay bounds revert to the normal regime. A single success
   * sets a "prior succeeded" flag; any intervening failure clears it.
   * <p>
   * The reset also clears {@code inExtended} so a subsequent UNEXPECTED
   * failure re-transitions into the extended regime (with the transition
   * detected exactly once, per {@link #onFailure(FailureClass)}'s contract).
   */
  void onSuccess() {
    if (this.priorPollWasSuccessful) {
      this.n = 0;
      this.inExtended = false;
      this.initialDelay = normalInterval;
      this.maxDelay = normalInterval;
    }
    this.priorPollWasSuccessful = true;
  }

  /**
   * Compute the delay before the next poll attempt:
   * {@code T = initialDelay * 2^(n-1)}, clamped to {@code maxDelay}. Jitter
   * {@code J} is uniform in {@code [0, T/2]}. Final wait is
   * {@code max(pollInterval, T - J)} — the wait never drops below the
   * customer-configured {@code pollInterval}.
   */
  Duration nextWait() {
    if (this.n <= 0) {
      return normalInterval;
    }
    long initialMs = initialDelay.toMillis();
    long maxMs = maxDelay.toMillis();
    double factor = Math.pow(2, this.n - 1);
    long tMs = (long) Math.min(initialMs * factor, (double) maxMs);
    long jitterMs = 0;
    long halfT = tMs / 2;
    if (halfT > 0) {
      jitterMs = (rng.nextLong() % halfT + halfT) % halfT;
    }
    long waitMs = tMs - jitterMs;
    long floorMs = normalInterval.toMillis();
    if (waitMs < floorMs) {
      waitMs = floorMs;
    }
    return Duration.ofMillis(waitMs);
  }

  private static Duration max(Duration a, Duration b) {
    return a.compareTo(b) >= 0 ? a : b;
  }

  // Accessors for observability / testing.

  int getN() { return n; }
  Duration getInitialDelay() { return initialDelay; }
  Duration getMaxDelay() { return maxDelay; }
  boolean getPriorPollWasSuccessful() { return priorPollWasSuccessful; }
}
