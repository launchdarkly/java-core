package com.launchdarkly.sdk.internal.events;

import com.launchdarkly.logging.LDLogLevel;
import com.launchdarkly.logging.LogCapture;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.launchdarkly.sdk.internal.events.DeliveryCooldownTracker.COOLDOWN_INITIAL_WINDOW_MILLIS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * These DefaultEventProcessor tests cover the delivery cooldown: an unexpected delivery failure
 * suppresses delivery for a bounded window instead of permanently disabling it.
 */
@SuppressWarnings("javadoc")
public class DefaultEventProcessorCooldownTest extends BaseEventTest {
  private final DeliveryCooldownTrackerTest.FakeNanoClock clock = new DeliveryCooldownTrackerTest.FakeNanoClock();

  // A deduplicator with the real component's shape: a context is new the first time it is seen,
  // and flush() forgets everything.
  static final class RememberingDeduplicator implements EventContextDeduplicator {
    private final Set<String> seen = new HashSet<>();
    private int flushCount = 0;

    @Override
    public Long getFlushInterval() {
      return null;
    }

    @Override
    public synchronized boolean processContext(LDContext context) {
      return seen.add(context.getFullyQualifiedKey());
    }

    @Override
    public synchronized void flush() {
      seen.clear();
      flushCount++;
    }

    synchronized int getFlushCount() {
      return flushCount;
    }
  }

  private DefaultEventProcessor makeProcessorWithClock(EventsConfigurationBuilder ec) {
    EventsConfiguration config = ec.build();
    config.cooldownNanoTimeFn = clock::now;
    return new DefaultEventProcessor(config, sharedExecutor, Thread.MAX_PRIORITY, testLogger);
  }

  // Drives the processor into a cooldown window: the sender fails one delivery with the given
  // result, and the test waits until the result has been handled. A single sync is a true
  // barrier, because the flush worker reports the result before it is counted inactive.
  private void enterCooldown(DefaultEventProcessor ep, MockEventSender es, EventSender.Result result)
      throws Exception {
    es.result = result;
    ep.sendEvent(identifyEvent(user));
    ep.flushAsync();
    es.awaitAnalytics();
    ep.waitUntilInactive();
    es.result = EventSender.Result.success(null);
  }

  private int countLogLines(LDLogLevel level, String substring) {
    int n = 0;
    for (LogCapture.Message message : logCapture.getMessages()) {
      if (message.getLevel() == level && message.getText().contains(substring)) {
        n++;
      }
    }
    return n;
  }

  @Test
  public void unexpectedErrorEntersCooldownAndDeliveryResumesAfterWindow() throws Exception {
    MockEventSender es = new MockEventSender();
    try (DefaultEventProcessor ep = makeProcessorWithClock(baseConfig(es))) {
      enterCooldown(ep, es, EventSender.Result.unexpectedFailure());

      // An in-window flush is a no-op: it makes no delivery attempt, and it disposes of the
      // drained events exactly as a delivered-and-refused payload would be disposed of.
      ep.sendEvent(identifyEvent(user));
      ep.flushBlocking();
      es.expectNoRequests(100);

      // Once the window has elapsed, delivery resumes: a fresh event is delivered by the probe,
      // and the discarded in-window event is not resurrected.
      clock.advanceMillis(COOLDOWN_INITIAL_WINDOW_MILLIS + 1000);
      Event.Identify postWindowEvent = identifyEvent(user);
      ep.sendEvent(postWindowEvent);
      ep.flushAsync();
      assertThat(es.getEventsFromLastRequest(), contains(isIdentifyEvent(postWindowEvent, userJson)));
    }
  }

  @SuppressWarnings("deprecation")
  @Test
  public void legacyMustShutDownResultEntersCooldownInsteadOfDisabling() throws Exception {
    MockEventSender es = new MockEventSender();
    try (DefaultEventProcessor ep = makeProcessorWithClock(baseConfig(es))) {
      // A custom EventSender written before the unexpected classification existed sets only the
      // legacy shutdown flag.
      enterCooldown(ep, es, new EventSender.Result(false, true, null));

      ep.sendEvent(identifyEvent(user));
      ep.flushBlocking();
      es.expectNoRequests(100);

      // Delivery is suppressed only for the window, not permanently.
      clock.advanceMillis(COOLDOWN_INITIAL_WINDOW_MILLIS + 1000);
      Event.Identify postWindowEvent = identifyEvent(user);
      ep.sendEvent(postWindowEvent);
      ep.flushAsync();
      assertThat(es.getEventsFromLastRequest(), contains(isIdentifyEvent(postWindowEvent, userJson)));
    }
  }

  @SuppressWarnings("deprecation")
  @Test
  public void resultClaimingBothSuccessAndShutdownIsTreatedAsUnexpected() throws Exception {
    // Only a custom EventSender can produce a result that claims success and an unexpected
    // failure at once; the failure takes precedence, so delivery health is never assumed from a
    // contradictory result.
    MockEventSender es = new MockEventSender();
    try (DefaultEventProcessor ep = makeProcessorWithClock(baseConfig(es))) {
      enterCooldown(ep, es, new EventSender.Result(true, true, null));

      ep.sendEvent(identifyEvent(user));
      ep.flushBlocking();
      es.expectNoRequests(100);
    }
  }

  @Test
  public void recoverableFailureDoesNotEnterCooldown() throws Exception {
    MockEventSender es = new MockEventSender();
    try (DefaultEventProcessor ep = makeProcessorWithClock(baseConfig(es))) {
      es.result = EventSender.Result.failure();
      ep.sendEvent(identifyEvent(user));
      ep.flushAsync();
      es.awaitAnalytics();
      ep.waitUntilInactive();

      // Delivery continues immediately; no window was opened.
      es.result = EventSender.Result.success(null);
      Event.Identify secondEvent = identifyEvent(user);
      ep.sendEvent(secondEvent);
      ep.flushAsync();
      assertThat(es.getEventsFromLastRequest(), contains(isIdentifyEvent(secondEvent, userJson)));
    }
  }

  @Test
  public void probeFailureOpensLongerWindow() throws Exception {
    MockEventSender es = new MockEventSender();
    try (DefaultEventProcessor ep = makeProcessorWithClock(baseConfig(es))) {
      enterCooldown(ep, es, EventSender.Result.unexpectedFailure());

      // The probe after the first window fails again; the second window is doubled, so a flush
      // that would be past a first window is still inside the second.
      clock.advanceMillis(COOLDOWN_INITIAL_WINDOW_MILLIS + 1000);
      enterCooldown(ep, es, EventSender.Result.unexpectedFailure());

      clock.advanceMillis(COOLDOWN_INITIAL_WINDOW_MILLIS + 1000); // inside the 10-minute second window
      ep.sendEvent(identifyEvent(user));
      ep.flushBlocking();
      es.expectNoRequests(100);

      // Past the second window, delivery resumes.
      clock.advanceMillis(COOLDOWN_INITIAL_WINDOW_MILLIS + 1000);
      Event.Identify postWindowEvent = identifyEvent(user);
      ep.sendEvent(postWindowEvent);
      ep.flushAsync();
      assertThat(es.getEventsFromLastRequest(), contains(isIdentifyEvent(postWindowEvent, userJson)));
    }
  }

  @Test
  public void shutdownDuringCooldownMakesNoDeliveryAttempt() throws Exception {
    MockEventSender es = new MockEventSender();
    try (DefaultEventProcessor ep = makeProcessorWithClock(baseConfig(es))) {
      enterCooldown(ep, es, EventSender.Result.unexpectedFailure());

      // The shutdown flush is in-window, so the buffered event is discarded without a network
      // attempt. (Closing twice is safe, so try-with-resources can close again after this.)
      ep.sendEvent(identifyEvent(user));
      ep.close();
      assertEquals(0, es.receivedParams.size());
    }
  }

  @Test
  public void identifyOnlyInWindowDiscardForgetsTheContext() throws Exception {
    MockEventSender es = new MockEventSender();
    RememberingDeduplicator deduplicator = new RememberingDeduplicator();
    try (DefaultEventProcessor ep = makeProcessorWithClock(baseConfig(es).contextDeduplicator(deduplicator))) {
      enterCooldown(ep, es, EventSender.Result.unexpectedFailure());

      // An identify event is a context introduction just as an index event is. The context is
      // introduced in-window (the window-open wipe cleared it), then discarded; the discard must
      // forget it again so a later payload re-introduces it.
      ep.sendEvent(identifyEvent(user));
      ep.flushBlocking();
      es.expectNoRequests(100);

      // The post-window payload introduces the context afresh: a custom event for the context
      // comes with an index event rather than referencing a context the service never received.
      clock.advanceMillis(COOLDOWN_INITIAL_WINDOW_MILLIS + 1000);
      Event.Custom customEvent = customEvent(user, "eventkey").build();
      ep.sendEvent(customEvent);
      ep.flushAsync();
      assertThat(es.getEventsFromLastRequest(), contains(
          isIndexEvent(customEvent, userJson),
          isCustomEvent(customEvent, userJson)));
    }
  }

  @Test
  public void windowOpenForgetsEvenDeliveredIntroductions() throws Exception {
    MockEventSender es = new MockEventSender();
    RememberingDeduplicator deduplicator = new RememberingDeduplicator();
    try (DefaultEventProcessor ep = makeProcessorWithClock(baseConfig(es).contextDeduplicator(deduplicator))) {
      // Introduce the context successfully before any window exists.
      ep.sendEvent(featureEvent(user, FLAG_KEY).build());
      ep.flushAsync();
      es.awaitAnalytics();
      ep.waitUntilInactive();

      // Opening a window forgets every context key, including ones whose index events were
      // delivered: the dispatcher cannot see which contexts the refused batch carried, and a
      // redundant index event after the window is harmless.
      enterCooldown(ep, es, EventSender.Result.unexpectedFailure());

      clock.advanceMillis(COOLDOWN_INITIAL_WINDOW_MILLIS + 1000);
      Event.FeatureRequest fe = featureEvent(user, FLAG_KEY).build();
      ep.sendEvent(fe);
      ep.flushAsync();
      assertThat(es.getEventsFromLastRequest(), contains(isIndexEvent(fe, userJson), isSummaryEvent()));
    }
  }

  @Test
  public void inWindowFlushWithNothingBufferedDoesNothing() throws Exception {
    MockEventSender es = new MockEventSender();
    try (DefaultEventProcessor ep = makeProcessorWithClock(baseConfig(es))) {
      enterCooldown(ep, es, EventSender.Result.unexpectedFailure());

      // Nothing is buffered, so there is no batch to dispose of and nothing to log.
      ep.flushBlocking();
      es.expectNoRequests(100);
      assertEquals(0, countLogLines(LDLogLevel.WARN, "Discarded"));
      assertEquals(0, countLogLines(LDLogLevel.DEBUG, "Discarded"));
    }
  }

  @Test
  public void discardLogsWarnOncePerWindowThenDebug() throws Exception {
    MockEventSender es = new MockEventSender();
    try (DefaultEventProcessor ep = makeProcessorWithClock(baseConfig(es))) {
      enterCooldown(ep, es, EventSender.Result.unexpectedFailure());
      assertEquals(1, countLogLines(LDLogLevel.ERROR, "will not be attempted"));

      for (int i = 0; i < 3; i++) {
        ep.sendEvent(identifyEvent(user));
        ep.flushBlocking();
      }
      assertEquals(1, countLogLines(LDLogLevel.WARN, "Discarded"));
      assertEquals(2, countLogLines(LDLogLevel.DEBUG, "Discarded"));

      // A second window gets its own single Warn, and the resumption is logged at Info.
      clock.advanceMillis(COOLDOWN_INITIAL_WINDOW_MILLIS + 1000);
      enterCooldown(ep, es, EventSender.Result.unexpectedFailure());
      ep.sendEvent(identifyEvent(user));
      ep.flushBlocking();
      assertEquals(2, countLogLines(LDLogLevel.WARN, "Discarded"));

      clock.advanceMillis(2 * COOLDOWN_INITIAL_WINDOW_MILLIS + 1000);
      ep.sendEvent(identifyEvent(user));
      ep.flushAsync();
      es.awaitAnalytics();
      ep.waitUntilInactive();
      assertEquals(1, countLogLines(LDLogLevel.INFO, "resumed after cooldown"));
    }
  }

  @Test
  public void diagnosticFailureDoesNotOpenACooldownWindow() throws Exception {
    // The cooldown governs analytics (flush) delivery only. An unexpected failure on a diagnostic
    // payload does not open a window, so analytics delivery is unaffected.
    LDValue fakePlatformData = LDValue.buildObject().put("cats", 2).build();
    DiagnosticStore diagnosticStore = new DiagnosticStore(
        new DiagnosticStore.SdkDiagnosticParams(SDK_KEY, "fake-sdk", "1.2.3", "fake-platform",
            fakePlatformData, null, null));
    MockEventSender es = new MockEventSender();
    es.result = EventSender.Result.unexpectedFailure();
    try (DefaultEventProcessor ep = makeProcessorWithClock(baseConfig(es).diagnosticStore(diagnosticStore))) {
      // The startup diagnostic event fails with the unexpected error.
      es.awaitDiagnostic();
      // The diagnostic send runs on the shared executor, which is single-threaded in these
      // tests, so a no-op task is a barrier for its response handling.
      sharedExecutor.submit(() -> {}).get();

      // No window opened: the next analytics flush is delivered rather than discarded.
      es.result = EventSender.Result.success(null);
      Event.Identify postEvent = identifyEvent(user);
      ep.sendEvent(postEvent);
      ep.flushAsync();
      assertThat(es.getEventsFromLastRequest(), contains(isIdentifyEvent(postEvent, userJson)));
    }
  }

  @Test
  public void diagnosticOutcomesDoNotAffectAnOpenCooldownWindow() throws Exception {
    // Diagnostic outcomes never move cooldown state: with an analytics window open, a diagnostic
    // failure does not step the ladder, and a diagnostic success after the window elapses does
    // not end it or log a resumption. Only analytics deliveries are the probe.
    LDValue fakePlatformData = LDValue.buildObject().put("cats", 2).build();
    DiagnosticStore diagnosticStore = new DiagnosticStore(
        new DiagnosticStore.SdkDiagnosticParams(SDK_KEY, "fake-sdk", "1.2.3", "fake-platform",
            fakePlatformData, null, null));
    MockEventSender es = new MockEventSender();
    try (DefaultEventProcessor ep = makeProcessorWithClock(baseConfig(es).diagnosticStore(diagnosticStore))) {
      es.awaitDiagnostic(); // the startup diagnostic event succeeds
      // The await returns when the payload is captured, before the diagnostic task has read the
      // result field; wait for the task to finish before changing the result.
      sharedExecutor.submit(() -> {}).get();
      enterCooldown(ep, es, EventSender.Result.unexpectedFailure());
      assertEquals(1, countLogLines(LDLogLevel.ERROR, "the next 300 seconds"));

      // A diagnostic failure during the window must not step the ladder.
      es.result = EventSender.Result.unexpectedFailure();
      ep.postDiagnostic();
      es.awaitDiagnostic();
      sharedExecutor.submit(() -> {}).get();

      // The window elapses; a diagnostic success must not end it or log a resumption.
      clock.advanceMillis(COOLDOWN_INITIAL_WINDOW_MILLIS + 1000);
      es.result = EventSender.Result.success(null);
      ep.postDiagnostic();
      es.awaitDiagnostic();
      sharedExecutor.submit(() -> {}).get();
      assertEquals(0, countLogLines(LDLogLevel.INFO, "resumed after cooldown"));

      // The next analytics failure steps to the doubled window: the diagnostic failure did not
      // advance the ladder (else this would be 1200), and the diagnostic success did not reset it
      // (else this would be 300).
      es.result = EventSender.Result.unexpectedFailure();
      ep.sendEvent(identifyEvent(user));
      ep.flushAsync();
      es.awaitAnalytics();
      ep.waitUntilInactive();
      assertEquals(1, countLogLines(LDLogLevel.ERROR, "the next 600 seconds"));
    }
  }

  @Test
  public void senderExceptionBreaksTheSuccessStreak() throws Exception {
    // A delivery that dies in an exception was not delivered, so it must restart the
    // reset-threshold measurement like any other failure (without opening a window).
    MockEventSender es = new MockEventSender();
    try (DefaultEventProcessor ep = makeProcessorWithClock(baseConfig(es))) {
      enterCooldown(ep, es, EventSender.Result.unexpectedFailure());
      assertEquals(1, countLogLines(LDLogLevel.ERROR, "the next 300 seconds"));

      // The probe succeeds: one credited success.
      clock.advanceMillis(COOLDOWN_INITIAL_WINDOW_MILLIS + 1000);
      ep.sendEvent(identifyEvent(user));
      ep.flushAsync();
      es.awaitAnalytics();
      ep.waitUntilInactive();

      // The next delivery throws inside the sender.
      es.fakeError = new RuntimeException("sorry");
      ep.sendEvent(identifyEvent(user));
      ep.flushAsync();
      es.awaitAnalytics();
      ep.waitUntilInactive();
      es.fakeError = null;

      // One more success: if the exception had been dropped, this would be the second
      // consecutive success and the ladder would reset.
      ep.sendEvent(identifyEvent(user));
      ep.flushAsync();
      es.awaitAnalytics();
      ep.waitUntilInactive();

      es.result = EventSender.Result.unexpectedFailure();
      ep.sendEvent(identifyEvent(user));
      ep.flushAsync();
      es.awaitAnalytics();
      ep.waitUntilInactive();
      assertEquals(1, countLogLines(LDLogLevel.ERROR, "the next 600 seconds"));
    }
  }

  @Test
  public void inWindowDiscardReportsZeroEventsInLastBatch() throws Exception {
    LDValue fakePlatformData = LDValue.buildObject().put("cats", 2).build();
    DiagnosticStore diagnosticStore = new DiagnosticStore(
        new DiagnosticStore.SdkDiagnosticParams(SDK_KEY, "fake-sdk", "1.2.3", "fake-platform",
            fakePlatformData, null, null));
    MockEventSender es = new MockEventSender();
    try (DefaultEventProcessor ep = makeProcessorWithClock(baseConfig(es).diagnosticStore(diagnosticStore))) {
      es.awaitDiagnostic(); // the startup diagnostic event
      // Barrier: let the startup diagnostic task finish before the first analytics flush.
      sharedExecutor.submit(() -> {}).get();

      // A delivered batch records its size.
      ep.sendEvent(identifyEvent(user));
      ep.flushAsync();
      es.awaitAnalytics();
      ep.waitUntilInactive();

      enterCooldown(ep, es, EventSender.Result.unexpectedFailure());
      ep.sendEvent(identifyEvent(user));
      ep.flushBlocking(); // discarded in-window

      // No batch reached the service, so the batch-size stat must not report one.
      ep.postDiagnostic();
      CapturedPayload diagnostic = es.awaitDiagnostic();
      assertEquals(0, LDValue.parse(diagnostic.data).get("eventsInLastBatch").intValue());
    }
  }

  @Test(timeout = 15000)
  public void nullResultFromSenderDoesNotStopDeliveryOrClose() throws Exception {
    // A broken custom EventSender returning null must not kill the delivery worker: delivery
    // continues and close() still completes.
    MockEventSender es = new MockEventSender();
    try (DefaultEventProcessor ep = makeProcessorWithClock(baseConfig(es))) {
      es.result = null;
      ep.sendEvent(identifyEvent(user));
      ep.flushAsync();
      es.awaitAnalytics();
      ep.waitUntilInactive(); // would hang here if the worker died

      es.result = EventSender.Result.success(null);
      Event.Identify secondEvent = identifyEvent(user);
      ep.sendEvent(secondEvent);
      ep.flushAsync();
      assertThat(es.getEventsFromLastRequest(), contains(isIdentifyEvent(secondEvent, userJson)));
    }
  }

  @Test
  public void nullResultBreaksTheSuccessStreak() throws Exception {
    // A null result reports no delivery, like an exception from the sender, so it must restart
    // the reset-threshold measurement and keep the ladder from resetting across it. (Go's result
    // is a value type whose zero value is already an ordinary failure, so this matches Go.)
    MockEventSender es = new MockEventSender();
    try (DefaultEventProcessor ep = makeProcessorWithClock(baseConfig(es))) {
      enterCooldown(ep, es, EventSender.Result.unexpectedFailure());
      assertEquals(1, countLogLines(LDLogLevel.ERROR, "the next 300 seconds"));

      // The probe succeeds: one credited success.
      clock.advanceMillis(COOLDOWN_INITIAL_WINDOW_MILLIS + 1000);
      ep.sendEvent(identifyEvent(user));
      ep.flushAsync();
      es.awaitAnalytics();
      ep.waitUntilInactive();

      // The next delivery returns null.
      es.result = null;
      ep.sendEvent(identifyEvent(user));
      ep.flushAsync();
      es.awaitAnalytics();
      ep.waitUntilInactive();

      // One more success: if the null had been ignored, this would be the second consecutive
      // success and the ladder would reset to the initial delay.
      es.result = EventSender.Result.success(null);
      ep.sendEvent(identifyEvent(user));
      ep.flushAsync();
      es.awaitAnalytics();
      ep.waitUntilInactive();

      es.result = EventSender.Result.unexpectedFailure();
      ep.sendEvent(identifyEvent(user));
      ep.flushAsync();
      es.awaitAnalytics();
      ep.waitUntilInactive();
      assertEquals(1, countLogLines(LDLogLevel.ERROR, "the next 600 seconds"));
    }
  }

  @Test(timeout = 15000)
  public void simultaneousFailuresAcrossAllWorkersStepTheLadderOnce() throws Exception {
    // Five deliveries are held in flight across the full worker pool and all fail unexpectedly at
    // once: exactly one window opens, and the incident steps the ladder exactly once.
    MockEventSender es = new MockEventSender();
    try (DefaultEventProcessor ep = makeProcessorWithClock(baseConfig(es))) {
      Object gate = new Object();
      es.waitSignal = gate;
      es.result = EventSender.Result.unexpectedFailure();
      for (int i = 0; i < EventsConfiguration.DEFAULT_EVENT_SENDING_THREAD_POOL_SIZE; i++) {
        CountDownLatch inFlight = new CountDownLatch(1);
        es.receivedCounter = inFlight;
        ep.sendEvent(identifyEvent(user));
        ep.flushAsync();
        assertTrue(inFlight.await(5, TimeUnit.SECONDS));
      }
      synchronized (gate) {
        gate.notifyAll();
      }
      es.waitSignal = null;
      ep.waitUntilInactive();
      assertEquals(1, countLogLines(LDLogLevel.ERROR, "the next 300 seconds"));
      assertEquals(0, countLogLines(LDLogLevel.ERROR, "the next 600 seconds"));

      // The probe's failure steps once, to 10 minutes.
      clock.advanceMillis(COOLDOWN_INITIAL_WINDOW_MILLIS + 1000);
      ep.sendEvent(identifyEvent(user));
      ep.flushAsync();
      ep.waitUntilInactive();
      assertEquals(1, countLogLines(LDLogLevel.ERROR, "the next 600 seconds"));
    }
  }

  @Test
  public void indexEventInWindowDiscardForgetsTheContext() throws Exception {
    // A custom event for a never-introduced context puts an index event in the batch; the
    // in-window discard destroys that introduction, so the deduplicator must forget it.
    MockEventSender es = new MockEventSender();
    RememberingDeduplicator deduplicator = new RememberingDeduplicator();
    try (DefaultEventProcessor ep = makeProcessorWithClock(baseConfig(es).contextDeduplicator(deduplicator))) {
      enterCooldown(ep, es, EventSender.Result.unexpectedFailure());

      // A custom event for a context not in the cache (the window-open wipe emptied it) carries
      // an index event; the in-window discard must forget that context again.
      ep.sendEvent(customEvent(user, "eventkey").build());
      ep.flushBlocking();
      es.expectNoRequests(100);

      // After the window, the context re-introduces rather than referencing one the service never
      // received.
      clock.advanceMillis(COOLDOWN_INITIAL_WINDOW_MILLIS + 1000);
      Event.Custom postEvent = customEvent(user, "eventkey").build();
      ep.sendEvent(postEvent);
      ep.flushAsync();
      assertThat(es.getEventsFromLastRequest(), contains(
          isIndexEvent(postEvent, userJson),
          isCustomEvent(postEvent, userJson)));
    }
  }
}
