package com.launchdarkly.sdk.internal.events;

import com.launchdarkly.sdk.LDValue;

import org.junit.Test;

import java.net.URI;

import static com.launchdarkly.testhelpers.JsonAssertions.jsonNull;
import static com.launchdarkly.testhelpers.JsonAssertions.jsonProperty;
import static com.launchdarkly.testhelpers.JsonTestValue.jsonOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertNotNull;

/**
 * These DefaultEventProcessor tests cover diagnostic event behavior.
 */
@SuppressWarnings("javadoc")
public class DefaultEventProcessorDiagnosticsTest extends BaseEventTest {
  private static LDValue fakePlatformData = LDValue.buildObject().put("cats", 2).build();
  
  private DiagnosticId diagnosticId;
  private DiagnosticStore diagnosticStore;
  
  public DefaultEventProcessorDiagnosticsTest() {
    diagnosticStore = new DiagnosticStore(
        new DiagnosticStore.SdkDiagnosticParams(
            SDK_KEY,
            "fake-sdk",
            "1.2.3",
            "fake-platform",
            fakePlatformData,
            null,
            null
            ));
    diagnosticId = diagnosticStore.getDiagnosticId();
  }
  
  @Test
  public void diagnosticEventsSentToDiagnosticEndpoint() throws Exception {
    MockEventSender es = new MockEventSender();
    try (DefaultEventProcessor ep = makeEventProcessor(baseConfig(es).diagnosticStore(diagnosticStore))) {
      CapturedPayload initReq = es.awaitDiagnostic();
      ep.postDiagnostic();
      CapturedPayload periodicReq = es.awaitDiagnostic();

      assertThat(initReq.diagnostic, is(true));
      assertThat(periodicReq.diagnostic, is(true));
    }
  }

  @Test
  public void initialDiagnosticEventHasInitBody() throws Exception {
    MockEventSender es = new MockEventSender();
    try (DefaultEventProcessor ep = makeEventProcessor(baseConfig(es).diagnosticStore(diagnosticStore))) {
      CapturedPayload req = es.awaitDiagnostic();

      assertThat(jsonOf(req.data), allOf(
          jsonProperty("kind", "diagnostic-init"),
          jsonProperty("id", jsonProperty("diagnosticId", diagnosticId.diagnosticId)),
          jsonProperty("id", jsonProperty("sdkKeySuffix", diagnosticId.sdkKeySuffix)),
          jsonProperty("configuration", not(jsonNull())),
          jsonProperty("sdk", not(jsonNull())),
          jsonProperty("platform", not(jsonNull()))
          ));
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  public void periodicDiagnosticEventHasStatisticsBody() throws Exception {
    MockEventSender es = new MockEventSender();
    long dataSinceDate = diagnosticStore.getDataSinceDate();
    try (DefaultEventProcessor ep = makeEventProcessor(baseConfig(es).diagnosticStore(diagnosticStore))) {
      // Ignore the initial diagnostic event
      es.awaitDiagnostic();
      ep.postDiagnostic();
      CapturedPayload periodicReq = es.awaitDiagnostic();

      assertThat(jsonOf(periodicReq.data), allOf(
          jsonProperty("kind", "diagnostic"),
          jsonProperty("id", jsonProperty("diagnosticId", diagnosticId.diagnosticId)),
          jsonProperty("id", jsonProperty("sdkKeySuffix", diagnosticId.sdkKeySuffix)),
          jsonProperty("dataSinceDate", dataSinceDate),
          jsonProperty("creationDate", diagnosticStore.getDataSinceDate()),
          jsonProperty("deduplicatedUsers", 0),
          jsonProperty("eventsInLastBatch", 0),
          jsonProperty("droppedEvents", 0)
          ));
    }
  }

  @Test
  public void periodicDiagnosticEventGetsEventsInLastBatchAndDeduplicatedUsers() throws Exception {
    MockEventSender es = new MockEventSender();
    Event.FeatureRequest fe1 = featureEvent(user, "flagkey1").build();
    Event.FeatureRequest fe2 = featureEvent(user, "flagkey2").build();

    // Create a fake deduplicator that just says "not seen" for the first call and "seen" thereafter
    EventContextDeduplicator contextDeduplicator = contextDeduplicatorThatSaysKeyIsNewOnFirstCallOnly();
    
    try (DefaultEventProcessor ep = makeEventProcessor(
        baseConfig(es).contextDeduplicator(contextDeduplicator).diagnosticStore(diagnosticStore))) {
      // Ignore the initial diagnostic event
      es.awaitDiagnostic();

      ep.sendEvent(fe1);
      ep.sendEvent(fe2);
      ep.flushAsync();
      // Ignore normal events
      es.awaitAnalytics();

      ep.postDiagnostic();
      CapturedPayload periodicReq = es.awaitRequest();

      assertNotNull(periodicReq);

      assertThat(jsonOf(periodicReq.data), allOf(
          jsonProperty("deduplicatedUsers", 1),
          jsonProperty("eventsInLastBatch", 2), // 1 index event + 1 summary event
          jsonProperty("droppedEvents", 0)
          ));
    }
  }

  @Test
  public void periodicDiagnosticEventsAreSentAutomatically() throws Exception {
    MockEventSender es = new MockEventSender();
    
    EventsConfigurationBuilder eventsConfig = makeEventsConfigurationWithBriefDiagnosticInterval(es);
    
    try (DefaultEventProcessor ep = makeEventProcessor(eventsConfig.diagnosticStore(diagnosticStore))) {
      // Ignore the initial diagnostic event
      es.awaitDiagnostic();

      CapturedPayload periodicReq = es.awaitRequest();

      assertNotNull(periodicReq);
      
      assertThat(jsonOf(periodicReq.data), jsonProperty("kind", "diagnostic"));
    }
  }

  @Test
  public void periodicDiagnosticEventsAreNotSentWhenInBackground() throws Exception {
    MockEventSender es = new MockEventSender();
    
    EventsConfigurationBuilder eventsConfig = makeEventsConfigurationWithBriefDiagnosticInterval(es);
    
    try (DefaultEventProcessor ep = makeEventProcessor(eventsConfig.diagnosticStore(diagnosticStore))) {
      // Ignore the initial diagnostic event
      es.awaitDiagnostic();

      // Expect a periodic diagnostic event
      es.awaitDiagnostic();

      // Now turn on background mode, which should make periodic events stop.
      ep.setInBackground(true);
      
      try {
        es.expectNoRequests(200);
      } catch (AssertionError e) {
        // Might have been a race condition where an event got scheduled before the background mode change;
        // if so, there should be a gap with no events after that, so try the assertion again.
        es.expectNoRequests(200);
      }
      
      // Turn off background mode; periodic events should resume
      ep.setInBackground(false);
      
      es.awaitDiagnostic();
    }
  }

  private EventsConfigurationBuilder makeEventsConfigurationWithBriefDiagnosticInterval(EventSender es) {
    return baseConfig(es).diagnosticRecordingIntervalMillis(50);
  }

  @Test
  public void diagnosticEventsStopAfter401Error() throws Exception {
    // This is easier to test with a mock component than it would be in LDClientEndToEndTest, because
    // we don't have to worry about the latency of a real HTTP request which could allow the periodic
    // task to fire again before we received a response. In real life, that wouldn't matter because
    // the minimum diagnostic interval is so long, but in a test we need to be able to use a short
    // interval.
    MockEventSender es = new MockEventSender();
    es.result = new EventSender.Result(false, true, null); // mustShutdown=true; this is what would be returned for a 401 error

    EventsConfigurationBuilder eventsConfig = makeEventsConfigurationWithBriefDiagnosticInterval(es);
    
    try (DefaultEventProcessor ep = makeEventProcessor(eventsConfig.diagnosticStore(diagnosticStore))) {
      // Ignore the initial diagnostic event
      es.awaitDiagnostic();

      es.expectNoRequests(100);
    }
  }
  
  @Test
  public void customBaseUriIsPassedToEventSenderForDiagnosticEvents() throws Exception {
    MockEventSender es = new MockEventSender();
    URI uri = URI.create("fake-uri");

    try (DefaultEventProcessor ep = makeEventProcessor(baseConfig(es).eventsUri(uri).diagnosticStore(diagnosticStore))) {
    }

    CapturedPayload p = es.awaitRequest();
    assertThat(p.eventsBaseUri, equalTo(uri));
  }
}
