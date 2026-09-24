package com.launchdarkly.integrations;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.server.Components;
import com.launchdarkly.sdk.server.DataSystemComponents;
import com.launchdarkly.sdk.server.LDClient;
import com.launchdarkly.sdk.server.LDConfig;
import com.launchdarkly.sdk.server.integrations.Hook;
import com.launchdarkly.sdk.server.interfaces.DataSourceDescriptor;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider.State;
import com.launchdarkly.testhelpers.httptest.Handler;
import com.launchdarkly.testhelpers.httptest.Handlers;
import com.launchdarkly.testhelpers.httptest.HttpServer;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.metrics.data.MetricData;
import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;

import static com.launchdarkly.integrations.MetricsHook.ATTR_DATA_SOURCE_NAME;
import static com.launchdarkly.integrations.MetricsHook.ATTR_DATA_SOURCE_STATE;
import static com.launchdarkly.integrations.MetricsHook.ATTR_ERROR_KIND;
import static com.launchdarkly.integrations.MetricsHook.ATTR_ERROR_TYPE;
import static com.launchdarkly.integrations.MetricsHook.ATTR_FLUSH_OUTCOME;
import static com.launchdarkly.integrations.MetricsHook.ATTR_HTTP_STATUS_CODE;
import static com.launchdarkly.integrations.MetricsHook.ATTR_INITIALIZATION_OUTCOME;
import static com.launchdarkly.integrations.MetricsHook.ATTR_INITIALIZER_OUTCOME;
import static com.launchdarkly.integrations.MetricsHook.ATTR_PREVIOUS_STATE;
import static com.launchdarkly.integrations.MetricsHook.ATTR_SYNCHRONIZER_CURRENT;
import static com.launchdarkly.integrations.MetricsHook.ATTR_SYNCHRONIZER_PREVIOUS;
import static com.launchdarkly.integrations.MetricsHook.ATTR_SYNCHRONIZER_REASON;
import static com.launchdarkly.integrations.MetricsHook.DATA_SYSTEM_DESCRIPTOR;
import static com.launchdarkly.integrations.MetricsHook.ERROR_TYPE_OTHER;
import static com.launchdarkly.integrations.MetricsHook.FLUSH_OUTCOME_SUCCEEDED;
import static com.launchdarkly.integrations.MetricsHook.METRIC_DATA_SOURCE_ERRORS;
import static com.launchdarkly.integrations.MetricsHook.METRIC_DATA_SOURCE_INTERRUPTED;
import static com.launchdarkly.integrations.MetricsHook.METRIC_DATA_SOURCE_STATE;
import static com.launchdarkly.integrations.MetricsHook.METRIC_DATA_SOURCE_STATE_DURATION;
import static com.launchdarkly.integrations.MetricsHook.METRIC_DATA_SOURCE_TRANSITIONS;
import static com.launchdarkly.integrations.MetricsHook.METRIC_EVENTS_DROPPED;
import static com.launchdarkly.integrations.MetricsHook.METRIC_EVENTS_FAILED;
import static com.launchdarkly.integrations.MetricsHook.METRIC_EVENTS_FLUSHES;
import static com.launchdarkly.integrations.MetricsHook.METRIC_EVENTS_SENT;
import static com.launchdarkly.integrations.MetricsHook.METRIC_INITIALIZATION_DURATION;
import static com.launchdarkly.integrations.MetricsHook.METRIC_INITIALIZER_ATTEMPTS;
import static com.launchdarkly.integrations.MetricsHook.METRIC_SYNCHRONIZER_ACTIVE;
import static com.launchdarkly.integrations.MetricsHook.METRIC_SYNCHRONIZER_TRANSITIONS;
import static com.launchdarkly.integrations.MetricsHook.NONE_VALUE;
import static com.launchdarkly.integrations.MetricsTestSupport.FDV1_STREAMING;
import static com.launchdarkly.integrations.MetricsTestSupport.POLLING;
import static com.launchdarkly.integrations.MetricsTestSupport.RELAY_POLL;
import static com.launchdarkly.integrations.MetricsTestSupport.STREAMING;
import static com.launchdarkly.integrations.MetricsTestSupport.Setup;
import static com.launchdarkly.integrations.MetricsTestSupport.hasMetric;
import static com.launchdarkly.integrations.MetricsTestSupport.histogramCount;
import static com.launchdarkly.integrations.MetricsTestSupport.metric;
import static com.launchdarkly.integrations.MetricsTestSupport.requireGaugeLong;
import static com.launchdarkly.integrations.MetricsTestSupport.requireMetric;
import static com.launchdarkly.integrations.MetricsTestSupport.sourceAttrs;
import static com.launchdarkly.integrations.MetricsTestSupport.sumDouble;
import static com.launchdarkly.integrations.MetricsTestSupport.sumLong;
import static com.launchdarkly.integrations.MetricsTestSupport.withState;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Drives a real client against fake LaunchDarkly endpoints, so that the SDK's own handler
 * invocations produce the metrics.
 */
@SuppressWarnings("javadoc")
public class MetricsHookClientTest {
  private static final String FLAG_JSON =
      "{\"key\":\"flag\",\"version\":1,\"on\":false,\"offVariation\":0,\"variations\":[true]}";
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private static String fdv1DataJson() {
    return "{\"flags\":{\"flag\":" + FLAG_JSON + "},\"segments\":{}}";
  }

  private static Handler fdv1StreamEvents() {
    return Handlers.all(Handlers.SSE.start(),
        Handlers.SSE.event("event: put\ndata: {\"data\":" + fdv1DataJson() + "}"));
  }

  private static Handler fdv1Stream() {
    return Handlers.all(fdv1StreamEvents(), Handlers.SSE.leaveOpen());
  }

  private static Handler fdv2Stream() {
    return Handlers.all(
        Handlers.SSE.start(),
        Handlers.SSE.event("event: server-intent\ndata: {\"payloads\":[{\"id\":\"payload-1\",\"target\":100,"
            + "\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}"),
        Handlers.SSE.event("event: put-object\ndata: {\"kind\":\"flag\",\"key\":\"flag\",\"version\":1,\"object\":"
            + FLAG_JSON + "}"),
        Handlers.SSE.event("event: payload-transferred\ndata: {\"state\":\"(p:payload-1:100)\",\"version\":100}"),
        Handlers.SSE.leaveOpen());
  }

  private static Handler fdv2Poll() {
    String body = "{\"events\":["
        + "{\"event\":\"server-intent\",\"data\":{\"payloads\":[{\"id\":\"payload-1\",\"target\":100,"
        + "\"intentCode\":\"xfer-full\",\"reason\":\"payload-missing\"}]}},"
        + "{\"event\":\"put-object\",\"data\":{\"kind\":\"flag\",\"key\":\"flag\",\"version\":1,\"object\":"
        + FLAG_JSON + "}},"
        + "{\"event\":\"payload-transferred\",\"data\":{\"state\":\"(p:payload-1:100)\",\"version\":100}}"
        + "]}";
    return Handlers.bodyJson(body);
  }

  private static LDConfig.Builder baseConfig(Setup setup) {
    return new LDConfig.Builder()
        .hooks(Components.hooks().setHooks(Collections.singletonList((Hook) setup.hook)))
        .diagnosticOptOut(true)
        .startWait(TIMEOUT);
  }

  private static Attributes transition(DataSourceDescriptor d, State current, String previous) {
    return Attributes.builder().putAll(withState(d, current)).put(ATTR_PREVIOUS_STATE, previous).build();
  }

  @Test
  public void fdv1StreamingClientReportsStatusesAndClosesCleanly() throws IOException {
    Setup setup = new Setup();
    // Connection sequence: a stream that ends, a 503, then a stream that stays open. This produces
    // one VALID -> INTERRUPTED -> VALID cycle with a single error response.
    Handler handler = Handlers.sequential(fdv1StreamEvents(), Handlers.status(503), fdv1Stream());
    try (HttpServer server = HttpServer.start(handler)) {
      LDConfig config = baseConfig(setup)
          .dataSource(Components.streamingDataSource().initialReconnectDelay(Duration.ofMillis(10)))
          .events(Components.noEvents())
          .serviceEndpoints(Components.serviceEndpoints().streaming(server.getUri()))
          .build();
      LDClient client = new LDClient("sdk-key", config);
      assertTrue(client.isInitialized());

      // The single data source is reported once as the initial synchronizer, and the first status
      // invocation carries the initial status.
      Collection<MetricData> metrics = setup.collect();
      assertEquals(1, sumLong(requireMetric(metrics, METRIC_SYNCHRONIZER_TRANSITIONS), Attributes.builder()
          .put(ATTR_SYNCHRONIZER_CURRENT, "streaming").put(ATTR_SYNCHRONIZER_REASON, "initial").build()));
      assertEquals(1, sumLong(requireMetric(metrics, METRIC_DATA_SOURCE_TRANSITIONS),
          transition(FDV1_STREAMING, State.INITIALIZING, NONE_VALUE)));

      // The stream ends, the reconnect gets the 503, and the next reconnect succeeds.
      metrics = setup.awaitMetrics(TIMEOUT, m -> {
        MetricData t = metric(m, METRIC_DATA_SOURCE_TRANSITIONS);
        return t != null && sumLong(t, transition(FDV1_STREAMING, State.VALID, State.INTERRUPTED.name())) >= 1;
      });
      assertEquals(1, sumLong(requireMetric(metrics, METRIC_DATA_SOURCE_ERRORS), Attributes.builder()
          .putAll(sourceAttrs(FDV1_STREAMING))
          .put(ATTR_ERROR_KIND, "ERROR_RESPONSE").put(ATTR_HTTP_STATUS_CODE, 503L).put(ATTR_ERROR_TYPE, "503").build()));
      assertEquals(1, requireGaugeLong(metrics, METRIC_DATA_SOURCE_STATE, withState(FDV1_STREAMING, State.VALID)));
      assertEquals(0, requireGaugeLong(metrics, METRIC_DATA_SOURCE_STATE, withState(FDV1_STREAMING, State.INTERRUPTED)));
      assertTrue(sumDouble(requireMetric(metrics, METRIC_DATA_SOURCE_INTERRUPTED), sourceAttrs(FDV1_STREAMING)) >= 0);

      // Closing the client reports OFF, then stops the observation of the state gauges.
      client.close();
      metrics = setup.collect();
      assertEquals(1, sumLong(requireMetric(metrics, METRIC_DATA_SOURCE_TRANSITIONS),
          transition(FDV1_STREAMING, State.OFF, State.VALID.name())));
      assertFalse(hasMetric(metrics, METRIC_DATA_SOURCE_STATE));
      assertFalse(hasMetric(metrics, METRIC_DATA_SOURCE_STATE_DURATION));
    }
  }

  @Test
  public void fdv2ClientReportsInitializersAndSynchronizer() throws IOException {
    Setup setup = new Setup();
    try (HttpServer failingPoll = HttpServer.start(Handlers.status(503));
         HttpServer healthyPoll = HttpServer.start(fdv2Poll());
         HttpServer stream = HttpServer.start(fdv2Stream())) {
      LDConfig config = baseConfig(setup)
          .events(Components.noEvents())
          .dataSystem(Components.dataSystem().custom()
              .initializers(
                  // A configured name tells two polling initializers apart in telemetry and logs.
                  DataSystemComponents.pollingInitializer()
                      .serviceEndpointsOverride(Components.serviceEndpoints().polling(failingPoll.getUri()))
                      .name("relay-poll"),
                  DataSystemComponents.pollingInitializer()
                      .serviceEndpointsOverride(Components.serviceEndpoints().polling(healthyPoll.getUri())))
              .synchronizers(
                  DataSystemComponents.streamingSynchronizer()
                      .serviceEndpointsOverride(Components.serviceEndpoints().streaming(stream.getUri()))))
          .build();
      LDClient client = new LDClient("sdk-key", config);
      assertTrue(client.isInitialized());

      // The synchronizer starts after initialization completes; wait for its first report.
      Collection<MetricData> metrics = setup.awaitMetrics(TIMEOUT, m -> hasMetric(m, METRIC_SYNCHRONIZER_ACTIVE));

      MetricData attempts = requireMetric(metrics, METRIC_INITIALIZER_ATTEMPTS);
      assertEquals(1, sumLong(attempts, Attributes.builder().putAll(sourceAttrs(RELAY_POLL))
          .put(ATTR_INITIALIZER_OUTCOME, "failed").build()));
      assertEquals(1, sumLong(attempts, Attributes.builder().putAll(sourceAttrs(POLLING))
          .put(ATTR_INITIALIZER_OUTCOME, "succeeded").build()));
      assertEquals(2, sumLong(attempts, Attributes.empty()));
      assertEquals(1, histogramCount(requireMetric(metrics, METRIC_INITIALIZATION_DURATION),
          Attributes.builder().putAll(sourceAttrs(POLLING)).put(ATTR_INITIALIZATION_OUTCOME, "succeeded").build()));

      assertEquals(1, sumLong(requireMetric(metrics, METRIC_SYNCHRONIZER_TRANSITIONS), Attributes.builder()
          .put(ATTR_SYNCHRONIZER_PREVIOUS, NONE_VALUE)
          .put(ATTR_SYNCHRONIZER_CURRENT, "streaming")
          .put(ATTR_SYNCHRONIZER_REASON, "initial").build()));
      assertEquals(1, requireGaugeLong(metrics, METRIC_SYNCHRONIZER_ACTIVE, sourceAttrs(STREAMING)));

      // The initial INITIALIZING status arrived before any initializer, so it belongs to the data
      // system itself. The VALID status followed the applied initializer, so it belongs to it.
      MetricData transitions = requireMetric(metrics, METRIC_DATA_SOURCE_TRANSITIONS);
      assertEquals(1, sumLong(transitions, transition(DATA_SYSTEM_DESCRIPTOR, State.INITIALIZING, NONE_VALUE)));
      assertEquals(1, sumLong(transitions, withState(POLLING, State.VALID)));

      // The synchronizer change moves the state gauge: the streaming synchronizer now reads VALID and
      // every earlier component reads 0.
      assertEquals(1, requireGaugeLong(metrics, METRIC_DATA_SOURCE_STATE, withState(STREAMING, State.VALID)));
      assertEquals(0, requireGaugeLong(metrics, METRIC_DATA_SOURCE_STATE, withState(POLLING, State.VALID)));
      assertEquals(0, requireGaugeLong(metrics, METRIC_DATA_SOURCE_STATE, withState(DATA_SYSTEM_DESCRIPTOR, State.VALID)));

      // Closing the client reports OFF for the streaming synchronizer before the hook stops observing.
      client.close();
      metrics = setup.collect();
      assertEquals(1, sumLong(requireMetric(metrics, METRIC_DATA_SOURCE_TRANSITIONS),
          transition(STREAMING, State.OFF, State.VALID.name())));
      assertFalse(hasMetric(metrics, METRIC_DATA_SOURCE_STATE));
      assertFalse(hasMetric(metrics, METRIC_SYNCHRONIZER_ACTIVE));
    }
  }

  @Test
  public void fdv2ClientReportsSynchronizerRemovalAndExhaustion() throws IOException {
    Setup setup = new Setup();
    // A 401 is unrecoverable: the only synchronizer is removed and none remain.
    try (HttpServer stream = HttpServer.start(Handlers.status(401))) {
      LDConfig config = baseConfig(setup)
          .events(Components.noEvents())
          .dataSystem(Components.dataSystem().custom()
              .synchronizers(DataSystemComponents.streamingSynchronizer()
                  .serviceEndpointsOverride(Components.serviceEndpoints().streaming(stream.getUri()))))
          .build();
      LDClient client = new LDClient("sdk-key", config);
      assertFalse(client.isInitialized());

      Collection<MetricData> metrics = setup.awaitMetrics(TIMEOUT, m -> hasMetric(m, METRIC_INITIALIZATION_DURATION));
      MetricData transitions = requireMetric(metrics, METRIC_SYNCHRONIZER_TRANSITIONS);
      assertEquals(1, sumLong(transitions, Attributes.builder()
          .put(ATTR_SYNCHRONIZER_CURRENT, "streaming").put(ATTR_SYNCHRONIZER_REASON, "initial").build()));
      assertEquals(1, sumLong(transitions, Attributes.builder()
          .put(ATTR_SYNCHRONIZER_PREVIOUS, "streaming")
          .put(ATTR_SYNCHRONIZER_CURRENT, NONE_VALUE)
          .put(ATTR_SYNCHRONIZER_REASON, "exhausted").build()));
      assertEquals(0, requireGaugeLong(metrics, METRIC_SYNCHRONIZER_ACTIVE, sourceAttrs(STREAMING)));
      assertEquals(1, histogramCount(requireMetric(metrics, METRIC_INITIALIZATION_DURATION),
          Attributes.of(ATTR_INITIALIZATION_OUTCOME, "failed")));
      assertEquals(1, sumLong(requireMetric(metrics, METRIC_DATA_SOURCE_ERRORS), Attributes.builder()
          .put(ATTR_DATA_SOURCE_NAME, "streaming").put(ATTR_HTTP_STATUS_CODE, 401L).build()));
      client.close();
    }
  }

  private static LDClient eventsClient(Setup setup, URI eventsUri, int capacity) {
    LDConfig config = baseConfig(setup)
        .dataSource(Components.externalUpdatesOnly())
        .events(Components.sendEvents().capacity(capacity).flushInterval(Duration.ofMinutes(5)))
        .serviceEndpoints(Components.serviceEndpoints().events(eventsUri))
        .build();
    return new LDClient("sdk-key", config);
  }

  @Test
  public void reportsDeliveredEvents() throws IOException {
    Setup setup = new Setup();
    try (HttpServer server = HttpServer.start(Handlers.status(202))) {
      LDClient client = eventsClient(setup, server.getUri(), 100);
      client.identify(LDContext.create("user-a"));
      client.identify(LDContext.create("user-b"));
      client.flush();
      Collection<MetricData> metrics = setup.awaitMetrics(TIMEOUT, m -> hasMetric(m, METRIC_EVENTS_SENT));
      assertEquals(2, sumLong(requireMetric(metrics, METRIC_EVENTS_SENT), Attributes.empty()));
      assertEquals(1, sumLong(requireMetric(metrics, METRIC_EVENTS_FLUSHES),
          Attributes.of(ATTR_FLUSH_OUTCOME, FLUSH_OUTCOME_SUCCEEDED)));
      assertFalse(hasMetric(metrics, METRIC_EVENTS_FAILED));
      client.close();
    }
  }

  @Test
  public void reportsFailedEventsWithAndWithoutAResponse() throws IOException {
    Setup setup = new Setup();
    try (HttpServer server = HttpServer.start(Handlers.status(503))) {
      LDClient client = eventsClient(setup, server.getUri(), 100);
      client.identify(LDContext.create("user-a"));
      client.identify(LDContext.create("user-b"));
      client.identify(LDContext.create("user-c"));
      client.flush();
      // The event sender retries a 503 once after a one second delay before giving up.
      Collection<MetricData> metrics = setup.awaitMetrics(TIMEOUT, m -> hasMetric(m, METRIC_EVENTS_FAILED));
      assertEquals(3, sumLong(requireMetric(metrics, METRIC_EVENTS_FAILED), Attributes.builder()
          .put(ATTR_ERROR_TYPE, "503").put(ATTR_HTTP_STATUS_CODE, 503L).build()));
      assertFalse(hasMetric(metrics, METRIC_EVENTS_SENT));
      client.close();
    }

    Setup noResponse = new Setup();
    LDClient client = eventsClient(noResponse, URI.create("http://127.0.0.1:1"), 100);
    client.identify(LDContext.create("user-a"));
    client.flush();
    Collection<MetricData> metrics = noResponse.awaitMetrics(TIMEOUT, m -> hasMetric(m, METRIC_EVENTS_FAILED));
    assertEquals(1, sumLong(requireMetric(metrics, METRIC_EVENTS_FAILED), Attributes.of(ATTR_ERROR_TYPE, ERROR_TYPE_OTHER)));
    client.close();
  }

  @Test
  public void reportsDroppedEventsWithTheNextFlush() throws IOException {
    Setup setup = new Setup();
    try (HttpServer server = HttpServer.start(Handlers.status(202))) {
      LDClient client = eventsClient(setup, server.getUri(), 1);
      for (int i = 0; i < 6; i++) {
        client.identify(LDContext.create("user-" + i));
      }
      // One event fits in the buffer; the others are discarded because the buffer, or the inbox in
      // front of it, was full. The drops are reported with the flush that follows them. A flush
      // request can itself be refused while the inbox is full, so the test keeps asking.
      Collection<MetricData> metrics = setup.awaitMetrics(TIMEOUT, m -> {
        client.flush();
        return hasMetric(m, METRIC_EVENTS_DROPPED);
      });
      assertTrue(sumLong(requireMetric(metrics, METRIC_EVENTS_DROPPED), Attributes.empty()) >= 1);
      assertTrue(sumLong(requireMetric(metrics, METRIC_EVENTS_SENT), Attributes.empty()) >= 1);
      client.close();
    }
  }
}
