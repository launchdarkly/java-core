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

      // An identify event is a context introduction just as an index event is; when an in-window
      // discard destroys it, the deduplicator must forget the context so a later payload can
      // introduce it again.
      ep.sendEvent(identifyEvent(user));
      ep.flushBlocking();
      es.expectNoRequests(100);
      assertEquals(1, deduplicator.getFlushCount());

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
  public void summaryOnlyInWindowDiscardKeepsAlreadyIntroducedContexts() throws Exception {
    MockEventSender es = new MockEventSender();
    RememberingDeduplicator deduplicator = new RememberingDeduplicator();
    try (DefaultEventProcessor ep = makeProcessorWithClock(baseConfig(es).contextDeduplicator(deduplicator))) {
      // Introduce the context successfully before any window exists.
      ep.sendEvent(featureEvent(user, FLAG_KEY).build());
      ep.flushAsync();
      es.awaitAnalytics();
      ep.waitUntilInactive();

      // A summary-only batch discarded in-window destroys no context introduction, so the
      // deduplicator must be left alone.
      enterCooldown(ep, es, EventSender.Result.unexpectedFailure());
      ep.sendEvent(featureEvent(user, FLAG_KEY).build());
      ep.flushBlocking();
      es.expectNoRequests(100);
      assertEquals(0, deduplicator.getFlushCount());

      // The post-window payload therefore carries no redundant index event.
      clock.advanceMillis(COOLDOWN_INITIAL_WINDOW_MILLIS + 1000);
      ep.sendEvent(featureEvent(user, FLAG_KEY).build());
      ep.flushAsync();
      assertThat(es.getEventsFromLastRequest(), contains(isSummaryEvent()));
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
  public void diagnosticFailureOpensWindowForAnalyticsDelivery() throws Exception {
    // The diagnostic delivery path reports unexpected failures into the cooldown, so an
    // unexpected failure on a diagnostic payload opens the window too. (Diagnostic sends
    // themselves are not gated by the window; see DefaultEventProcessorDiagnosticsTest.)
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

      // Analytics delivery is now in-window.
      es.result = EventSender.Result.success(null);
      ep.sendEvent(identifyEvent(user));
      ep.flushBlocking();
      es.expectNoRequests(100);
    }
  }

  @Test
  public void diagnosticSuccessesDoNotResetTheLadderOrEndTheWindow() throws Exception {
    // A diagnostic success is not the probe: only analytics deliveries prove the service is
    // accepting analytics payloads again, so diagnostic successes neither end a window, nor log a
    // resumption, nor count toward the reset threshold.
    LDValue fakePlatformData = LDValue.buildObject().put("cats", 2).build();
    DiagnosticStore diagnosticStore = new DiagnosticStore(
        new DiagnosticStore.SdkDiagnosticParams(SDK_KEY, "fake-sdk", "1.2.3", "fake-platform",
            fakePlatformData, null, null));
    MockEventSender es = new MockEventSender();
    try (DefaultEventProcessor ep = makeProcessorWithClock(baseConfig(es).diagnosticStore(diagnosticStore))) {
      es.awaitDiagnostic(); // the startup diagnostic event succeeds
      // The await returns when the payload is captured, which is before the diagnostic task has
      // read the sender's result field; wait for the task to finish before changing the result,
      // or the startup diagnostic can consume the failure and open the window itself.
      sharedExecutor.submit(() -> {}).get();
      enterCooldown(ep, es, EventSender.Result.unexpectedFailure());
      assertEquals(1, countLogLines(LDLogLevel.ERROR, "the next 300 seconds"));

      // The window elapses; two diagnostic successes land before any analytics delivery.
      clock.advanceMillis(COOLDOWN_INITIAL_WINDOW_MILLIS + 1000);
      for (int i = 0; i < 2; i++) {
        ep.postDiagnostic();
        es.awaitDiagnostic();
        sharedExecutor.submit(() -> {}).get(); // barrier for the diagnostic response handling
      }
      assertEquals(0, countLogLines(LDLogLevel.INFO, "resumed after cooldown"));

      // The ladder did not reset: the next unexpected analytics failure opens the doubled window.
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
      sharedExecutor.submit(() -> {}).get(); // see diagnosticSuccessesDoNotResetTheLadderOrEndTheWindow

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

  @Test(timeout = 15000)
  public void diagnosticStraddlingTheWindowEndCannotStepTheLadder() throws Exception {
    // A diagnostic dispatched inside the window gets no live token, so even if it is still in
    // flight when the window elapses, its unexpected failure cannot step the ladder or displace
    // the analytics probe.
    LDValue fakePlatformData = LDValue.buildObject().put("cats", 2).build();
    DiagnosticStore diagnosticStore = new DiagnosticStore(
        new DiagnosticStore.SdkDiagnosticParams(SDK_KEY, "fake-sdk", "1.2.3", "fake-platform",
            fakePlatformData, null, null));
    MockEventSender es = new MockEventSender();
    try (DefaultEventProcessor ep = makeProcessorWithClock(baseConfig(es).diagnosticStore(diagnosticStore))) {
      es.awaitDiagnostic(); // the startup diagnostic event succeeds
      sharedExecutor.submit(() -> {}).get(); // see diagnosticSuccessesDoNotResetTheLadderOrEndTheWindow
      enterCooldown(ep, es, EventSender.Result.unexpectedFailure());
      assertEquals(1, countLogLines(LDLogLevel.ERROR, "the next 300 seconds"));

      Object gate = new Object();
      CountDownLatch inFlight = new CountDownLatch(1);
      es.receivedCounter = inFlight;
      es.waitSignal = gate;
      es.result = EventSender.Result.unexpectedFailure();
      ep.postDiagnostic();
      assertTrue(inFlight.await(5, TimeUnit.SECONDS)); // the diagnostic send is parked in-flight
      es.awaitDiagnostic(); // consume its capture, which was recorded before it parked

      clock.advanceMillis(COOLDOWN_INITIAL_WINDOW_MILLIS + 1000); // the window elapses meanwhile
      synchronized (gate) {
        gate.notifyAll();
      }
      es.waitSignal = null;
      sharedExecutor.submit(() -> {}).get(); // the diagnostic outcome has now been handled

      // The analytics probe's failure steps the ladder exactly once, to 10 minutes: had the
      // straddling diagnostic stepped it, this would be 20.
      ep.sendEvent(identifyEvent(user));
      ep.flushAsync();
      es.awaitAnalytics();
      ep.waitUntilInactive();
      assertEquals(1, countLogLines(LDLogLevel.ERROR, "the next 600 seconds"));
      assertEquals(0, countLogLines(LDLogLevel.ERROR, "the next 1200 seconds"));
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

      LDContext otherContext = LDContext.create("otherkey");
      ep.sendEvent(customEvent(otherContext, "eventkey").build());
      ep.flushBlocking();
      es.expectNoRequests(100);
      assertEquals(1, deduplicator.getFlushCount());
    }
  }
}
