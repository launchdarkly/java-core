package com.launchdarkly.integrations;

import com.launchdarkly.sdk.server.integrations.DataSourceStatusContext;
import com.launchdarkly.sdk.server.integrations.EventFlushContext;
import com.launchdarkly.sdk.server.integrations.InitializerContext;
import com.launchdarkly.sdk.server.integrations.SynchronizerChangeContext;
import com.launchdarkly.sdk.server.interfaces.DataSourceDescriptor;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider.ErrorInfo;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider.ErrorKind;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider.State;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider.Status;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;

import static com.launchdarkly.integrations.MetricsHook.ATTR_DATA_SOURCE_NAME;
import static com.launchdarkly.integrations.MetricsHook.ATTR_DATA_SOURCE_PROTOCOL;
import static com.launchdarkly.integrations.MetricsHook.ATTR_DATA_SOURCE_STATE;
import static com.launchdarkly.integrations.MetricsHook.ATTR_DATA_SOURCE_TRANSPORT;
import static com.launchdarkly.integrations.MetricsHook.ATTR_ERROR_TYPE;
import static com.launchdarkly.integrations.MetricsHook.ATTR_FLUSH_OUTCOME;
import static com.launchdarkly.integrations.MetricsHook.ATTR_HTTP_STATUS_CODE;
import static com.launchdarkly.integrations.MetricsHook.ATTR_INITIALIZER_OUTCOME;
import static com.launchdarkly.integrations.MetricsHook.ATTR_PREVIOUS_STATE;
import static com.launchdarkly.integrations.MetricsHook.ATTR_SYNCHRONIZER_CURRENT;
import static com.launchdarkly.integrations.MetricsHook.ATTR_SYNCHRONIZER_PREVIOUS;
import static com.launchdarkly.integrations.MetricsHook.ATTR_SYNCHRONIZER_REASON;
import static com.launchdarkly.integrations.MetricsHook.DATA_SYSTEM_DESCRIPTOR;
import static com.launchdarkly.integrations.MetricsHook.DATA_SYSTEM_NAME;
import static com.launchdarkly.integrations.MetricsHook.ERROR_TYPE_OTHER;
import static com.launchdarkly.integrations.MetricsHook.FLUSH_OUTCOME_FAILED;
import static com.launchdarkly.integrations.MetricsHook.FLUSH_OUTCOME_SUCCEEDED;
import static com.launchdarkly.integrations.MetricsHook.METRIC_DATA_SOURCE_ERRORS;
import static com.launchdarkly.integrations.MetricsHook.METRIC_DATA_SOURCE_INTERRUPTED;
import static com.launchdarkly.integrations.MetricsHook.METRIC_DATA_SOURCE_INTERRUPTION;
import static com.launchdarkly.integrations.MetricsHook.METRIC_DATA_SOURCE_STATE;
import static com.launchdarkly.integrations.MetricsHook.METRIC_DATA_SOURCE_STATE_DURATION;
import static com.launchdarkly.integrations.MetricsHook.METRIC_DATA_SOURCE_TRANSITIONS;
import static com.launchdarkly.integrations.MetricsHook.METRIC_EVENTS_BATCH_SIZE;
import static com.launchdarkly.integrations.MetricsHook.METRIC_EVENTS_DROPPED;
import static com.launchdarkly.integrations.MetricsHook.METRIC_EVENTS_FAILED;
import static com.launchdarkly.integrations.MetricsHook.METRIC_EVENTS_FLUSHES;
import static com.launchdarkly.integrations.MetricsHook.METRIC_EVENTS_FLUSH_DURATION;
import static com.launchdarkly.integrations.MetricsHook.METRIC_EVENTS_SENT;
import static com.launchdarkly.integrations.MetricsHook.METRIC_EVENTS_SENT_SIZE;
import static com.launchdarkly.integrations.MetricsHook.METRIC_INITIALIZER_ATTEMPTS;
import static com.launchdarkly.integrations.MetricsHook.METRIC_SYNCHRONIZER_ACTIVE;
import static com.launchdarkly.integrations.MetricsHook.METRIC_SYNCHRONIZER_TRANSITIONS;
import static com.launchdarkly.integrations.MetricsHook.NONE_VALUE;
import static com.launchdarkly.integrations.MetricsHook.NOT_PROVIDED_VALUE;
import static com.launchdarkly.integrations.MetricsTestSupport.POLLING;
import static com.launchdarkly.integrations.MetricsTestSupport.RELAY_POLL;
import static com.launchdarkly.integrations.MetricsTestSupport.STREAMING;
import static com.launchdarkly.integrations.MetricsTestSupport.Setup;
import static com.launchdarkly.integrations.MetricsTestSupport.gaugeDouble;
import static com.launchdarkly.integrations.MetricsTestSupport.gaugeLong;
import static com.launchdarkly.integrations.MetricsTestSupport.hasMetric;
import static com.launchdarkly.integrations.MetricsTestSupport.histogramCount;
import static com.launchdarkly.integrations.MetricsTestSupport.requireGaugeLong;
import static com.launchdarkly.integrations.MetricsTestSupport.requireMetric;
import static com.launchdarkly.integrations.MetricsTestSupport.sourceAttrs;
import static com.launchdarkly.integrations.MetricsTestSupport.statusAt;
import static com.launchdarkly.integrations.MetricsTestSupport.sumDouble;
import static com.launchdarkly.integrations.MetricsTestSupport.sumLong;
import static com.launchdarkly.integrations.MetricsTestSupport.withState;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * These tests drive the handlers directly, so that the order of the invocations and the times in
 * the statuses are exact.
 */
@SuppressWarnings("javadoc")
public class MetricsHookTest {
  private static void reportStatus(MetricsHook hook, Status previous, Status current) {
    hook.dataSourceStatusChanged(new DataSourceStatusContext(previous, current));
  }

  private static void reportSynchronizer(MetricsHook hook, DataSourceDescriptor previous,
      DataSourceDescriptor current, SynchronizerChangeContext.Reason reason) {
    hook.synchronizerChanged(new SynchronizerChangeContext(previous, current, reason, null));
  }

  private static Attributes systemWithState(State state) {
    return Attributes.builder()
        .put(ATTR_DATA_SOURCE_NAME, DATA_SYSTEM_NAME)
        .put(ATTR_DATA_SOURCE_PROTOCOL, NOT_PROVIDED_VALUE)
        .put(ATTR_DATA_SOURCE_TRANSPORT, NOT_PROVIDED_VALUE)
        .put(ATTR_DATA_SOURCE_STATE, state.name())
        .build();
  }

  @Test
  public void attributesStatusesBeforeAnyComponentToTheDataSystem() {
    Setup setup = new Setup();
    reportStatus(setup.hook, null, statusAt(State.INITIALIZING, Instant.now()));

    Collection<MetricData> metrics = setup.collect();
    assertEquals(1, requireGaugeLong(metrics, METRIC_DATA_SOURCE_STATE, systemWithState(State.INITIALIZING)));
    assertEquals(1, sumLong(requireMetric(metrics, METRIC_DATA_SOURCE_TRANSITIONS),
        Attributes.builder().putAll(systemWithState(State.INITIALIZING)).put(ATTR_PREVIOUS_STATE, NONE_VALUE).build()));
    // One component, four states.
    assertEquals(4, requireMetric(metrics, METRIC_DATA_SOURCE_STATE).getLongGaugeData().getPoints().size());
  }

  @Test
  public void movesTheStateToAnAppliedInitializerBeforeTheNextStatus() {
    Setup setup = new Setup();
    Status initializing = statusAt(State.INITIALIZING, Instant.now());
    reportStatus(setup.hook, null, initializing);
    setup.hook.initializerCompleted(new InitializerContext(RELAY_POLL, InitializerContext.Outcome.FAILED,
        new ErrorInfo(ErrorKind.NETWORK_ERROR, 0, "connection refused", Instant.now()), Duration.ofMillis(10), false));
    setup.hook.initializerCompleted(new InitializerContext(POLLING, InitializerContext.Outcome.SUCCEEDED,
        null, Duration.ofMillis(20), true));

    // Between the applied initializer and the next status, the initializer already reports the state
    // that carried over. The failed initializer never reports a state.
    Collection<MetricData> metrics = setup.collect();
    assertEquals(1, requireGaugeLong(metrics, METRIC_DATA_SOURCE_STATE, withState(POLLING, State.INITIALIZING)));
    assertEquals(0, requireGaugeLong(metrics, METRIC_DATA_SOURCE_STATE, systemWithState(State.INITIALIZING)));
    assertNull(gaugeLong(requireMetric(metrics, METRIC_DATA_SOURCE_STATE), sourceAttrs(RELAY_POLL)));

    MetricData attempts = requireMetric(metrics, METRIC_INITIALIZER_ATTEMPTS);
    assertEquals(1, sumLong(attempts, Attributes.builder().putAll(sourceAttrs(RELAY_POLL))
        .put(ATTR_INITIALIZER_OUTCOME, "failed").build()));
    assertEquals(1, sumLong(attempts, Attributes.builder().putAll(sourceAttrs(POLLING))
        .put(ATTR_INITIALIZER_OUTCOME, "succeeded").build()));

    reportStatus(setup.hook, initializing, statusAt(State.VALID, Instant.now()));
    metrics = setup.collect();
    assertEquals(1, requireGaugeLong(metrics, METRIC_DATA_SOURCE_STATE, withState(POLLING, State.VALID)));
    assertEquals(1, sumLong(requireMetric(metrics, METRIC_DATA_SOURCE_TRANSITIONS),
        Attributes.builder().putAll(withState(POLLING, State.VALID))
            .put(ATTR_PREVIOUS_STATE, State.INITIALIZING.name()).build()));
  }

  @Test
  public void chargesAnInterruptionToTheComponentThatFailed() {
    Setup setup = new Setup();
    Instant start = Instant.now().minusSeconds(60);
    reportSynchronizer(setup.hook, DataSourceDescriptor.empty(), STREAMING, SynchronizerChangeContext.Reason.INITIAL);
    Status initializing = statusAt(State.INITIALIZING, start);
    Status valid = statusAt(State.VALID, start.plusSeconds(1));
    ErrorInfo error = new ErrorInfo(ErrorKind.NETWORK_ERROR, 0, "connection reset", start.plusSeconds(10));
    Status interrupted = statusAt(State.INTERRUPTED, start.plusSeconds(10), error);
    reportStatus(setup.hook, null, initializing);
    reportStatus(setup.hook, initializing, valid);
    reportStatus(setup.hook, valid, interrupted);

    // The polling synchronizer takes over while the data source is interrupted.
    reportSynchronizer(setup.hook, STREAMING, POLLING, SynchronizerChangeContext.Reason.FALLBACK);
    Status recovered = statusAt(State.VALID, start.plusSeconds(40), error);
    reportStatus(setup.hook, interrupted, recovered);

    Collection<MetricData> metrics = setup.collect();
    MetricData interruptedTime = requireMetric(metrics, METRIC_DATA_SOURCE_INTERRUPTED);
    assertEquals(30.0, sumDouble(interruptedTime, sourceAttrs(STREAMING)), 0.001);
    assertEquals(0.0, sumDouble(interruptedTime, sourceAttrs(POLLING)), 0.0);
    assertEquals(1, histogramCount(requireMetric(metrics, METRIC_DATA_SOURCE_INTERRUPTION), sourceAttrs(STREAMING)));
    assertEquals(1, sumLong(requireMetric(metrics, METRIC_DATA_SOURCE_ERRORS), sourceAttrs(STREAMING)));

    // The recovery itself is reported by the component that took over.
    assertEquals(1, sumLong(requireMetric(metrics, METRIC_DATA_SOURCE_TRANSITIONS),
        Attributes.builder().putAll(withState(POLLING, State.VALID))
            .put(ATTR_PREVIOUS_STATE, State.INTERRUPTED.name()).build()));
    assertEquals(1, requireGaugeLong(metrics, METRIC_DATA_SOURCE_STATE, withState(POLLING, State.VALID)));
    assertEquals(0, requireGaugeLong(metrics, METRIC_DATA_SOURCE_STATE, withState(STREAMING, State.VALID)));
    assertEquals(0, requireGaugeLong(metrics, METRIC_DATA_SOURCE_STATE, withState(STREAMING, State.INTERRUPTED)));
    assertEquals(1, requireGaugeLong(metrics, METRIC_SYNCHRONIZER_ACTIVE, sourceAttrs(POLLING)));
    assertEquals(0, requireGaugeLong(metrics, METRIC_SYNCHRONIZER_ACTIVE, sourceAttrs(STREAMING)));

    // The time in VALID counts from the takeover, not from the earlier stateSince.
    Double age = gaugeDouble(requireMetric(metrics, METRIC_DATA_SOURCE_STATE_DURATION), withState(POLLING, State.VALID));
    assertNotNull(age);
    assertTrue("age " + age, age < 5.0);
  }

  @Test
  public void keepsTheComponentWhenSynchronizersAreExhausted() {
    Setup setup = new Setup();
    reportSynchronizer(setup.hook, DataSourceDescriptor.empty(), STREAMING, SynchronizerChangeContext.Reason.INITIAL);
    Status initializing = statusAt(State.INITIALIZING, Instant.now());
    Status valid = statusAt(State.VALID, Instant.now());
    reportStatus(setup.hook, null, initializing);
    reportStatus(setup.hook, initializing, valid);
    reportSynchronizer(setup.hook, STREAMING, DataSourceDescriptor.empty(), SynchronizerChangeContext.Reason.EXHAUSTED);
    reportStatus(setup.hook, valid, statusAt(State.OFF, Instant.now()));

    Collection<MetricData> metrics = setup.collect();
    assertEquals(1, requireGaugeLong(metrics, METRIC_DATA_SOURCE_STATE, withState(STREAMING, State.OFF)));
    assertEquals(0, requireGaugeLong(metrics, METRIC_SYNCHRONIZER_ACTIVE, sourceAttrs(STREAMING)));
    assertEquals(1, sumLong(requireMetric(metrics, METRIC_SYNCHRONIZER_TRANSITIONS), Attributes.builder()
        .put(ATTR_SYNCHRONIZER_PREVIOUS, "streaming")
        .put(ATTR_SYNCHRONIZER_CURRENT, NONE_VALUE)
        .put(ATTR_SYNCHRONIZER_REASON, "exhausted").build()));
  }

  @Test
  public void reportsAnUnnamedComponentAsNotProvided() {
    Setup setup = new Setup();
    DataSourceDescriptor unnamed = DataSourceDescriptor.of(
        DataSourceDescriptor.Protocol.FDV2, DataSourceDescriptor.Transport.POLLING, null);
    reportSynchronizer(setup.hook, DataSourceDescriptor.empty(), unnamed, SynchronizerChangeContext.Reason.INITIAL);
    reportStatus(setup.hook, null, statusAt(State.VALID, Instant.now()));

    Collection<MetricData> metrics = setup.collect();
    assertEquals(1, sumLong(requireMetric(metrics, METRIC_SYNCHRONIZER_TRANSITIONS), Attributes.builder()
        .put(ATTR_SYNCHRONIZER_PREVIOUS, NONE_VALUE)
        .put(ATTR_SYNCHRONIZER_CURRENT, NOT_PROVIDED_VALUE).build()));
    assertEquals(1, requireGaugeLong(metrics, METRIC_DATA_SOURCE_STATE, Attributes.builder()
        .put(ATTR_DATA_SOURCE_NAME, NOT_PROVIDED_VALUE)
        .put(ATTR_DATA_SOURCE_PROTOCOL, "fdv2")
        .put(ATTR_DATA_SOURCE_TRANSPORT, "polling")
        .put(ATTR_DATA_SOURCE_STATE, State.VALID.name()).build()));
  }

  @Test
  public void clampsANegativeInterruptionToZero() {
    Setup setup = new Setup();
    Instant now = Instant.now();
    reportSynchronizer(setup.hook, DataSourceDescriptor.empty(), STREAMING, SynchronizerChangeContext.Reason.INITIAL);
    Status valid = statusAt(State.VALID, now);
    // The clock stepped back: the recovery is stamped earlier than the interruption.
    Status interrupted = statusAt(State.INTERRUPTED, now.plusSeconds(10));
    reportStatus(setup.hook, null, valid);
    reportStatus(setup.hook, valid, interrupted);
    reportStatus(setup.hook, interrupted, statusAt(State.VALID, now.plusSeconds(5)));

    Collection<MetricData> metrics = setup.collect();
    assertEquals(0.0, sumDouble(requireMetric(metrics, METRIC_DATA_SOURCE_INTERRUPTED), Attributes.empty()), 0.0);
    assertEquals(1, histogramCount(requireMetric(metrics, METRIC_DATA_SOURCE_INTERRUPTION), Attributes.empty()));
  }

  @Test
  public void stopsObservingWhenClosed() {
    Setup setup = new Setup();
    reportSynchronizer(setup.hook, DataSourceDescriptor.empty(), STREAMING, SynchronizerChangeContext.Reason.INITIAL);
    reportStatus(setup.hook, null, statusAt(State.VALID, Instant.now()));
    Collection<MetricData> metrics = setup.collect();
    assertTrue(hasMetric(metrics, METRIC_DATA_SOURCE_STATE));
    assertTrue(hasMetric(metrics, METRIC_SYNCHRONIZER_ACTIVE));

    setup.hook.close();
    metrics = setup.collect();
    assertFalse(hasMetric(metrics, METRIC_DATA_SOURCE_STATE));
    assertFalse(hasMetric(metrics, METRIC_DATA_SOURCE_STATE_DURATION));
    assertFalse(hasMetric(metrics, METRIC_SYNCHRONIZER_ACTIVE));
    // The counters keep what they recorded.
    assertTrue(hasMetric(metrics, METRIC_SYNCHRONIZER_TRANSITIONS));

    setup.hook.close(); // a second close is harmless
  }

  @Test
  public void addsConfiguredAttributes() {
    Attributes clientName = Attributes.of(AttributeKey.stringKey("launchdarkly.client"), "checkout");
    Setup setup = new Setup(clientName);
    reportStatus(setup.hook, null, statusAt(State.VALID, Instant.now()));
    setup.hook.eventFlushCompleted(new EventFlushContext(3, 100, true, 202, Duration.ofMillis(10), 0));

    Collection<MetricData> metrics = setup.collect();
    assertEquals(1, requireGaugeLong(metrics, METRIC_DATA_SOURCE_STATE,
        Attributes.builder().putAll(clientName).put(ATTR_DATA_SOURCE_STATE, State.VALID.name()).build()));
    assertEquals(3, sumLong(requireMetric(metrics, METRIC_EVENTS_SENT), clientName));
    assertEquals(1, sumLong(requireMetric(metrics, METRIC_EVENTS_FLUSHES),
        Attributes.builder().putAll(clientName).put(ATTR_FLUSH_OUTCOME, FLUSH_OUTCOME_SUCCEEDED).build()));
    assertEquals(1, sumLong(requireMetric(metrics, METRIC_DATA_SOURCE_TRANSITIONS), clientName));
  }

  @Test
  public void recordsDeliveredFailedAndDroppedEvents() {
    Setup setup = new Setup();
    setup.hook.eventFlushCompleted(new EventFlushContext(2, 400, true, 202, Duration.ofMillis(15), 0));
    setup.hook.eventFlushCompleted(new EventFlushContext(3, 600, false, 503, Duration.ofSeconds(1), 4));
    setup.hook.eventFlushCompleted(new EventFlushContext(1, 200, false, 0, Duration.ofSeconds(1), 0));

    Collection<MetricData> metrics = setup.collect();
    assertEquals(2, sumLong(requireMetric(metrics, METRIC_EVENTS_SENT), Attributes.empty()));
    assertEquals(400, sumLong(requireMetric(metrics, METRIC_EVENTS_SENT_SIZE), Attributes.empty()));
    assertEquals(4, sumLong(requireMetric(metrics, METRIC_EVENTS_DROPPED), Attributes.empty()));

    MetricData failed = requireMetric(metrics, METRIC_EVENTS_FAILED);
    assertEquals(3, sumLong(failed, Attributes.builder()
        .put(ATTR_ERROR_TYPE, "503").put(ATTR_HTTP_STATUS_CODE, 503L).build()));
    assertEquals(1, sumLong(failed, Attributes.of(ATTR_ERROR_TYPE, ERROR_TYPE_OTHER)));
    for (LongPointData p : failed.getLongSumData().getPoints()) {
      if (ERROR_TYPE_OTHER.equals(p.getAttributes().get(ATTR_ERROR_TYPE))) {
        assertNull("no status code for a flush without a response", p.getAttributes().get(ATTR_HTTP_STATUS_CODE));
      }
    }

    MetricData flushes = requireMetric(metrics, METRIC_EVENTS_FLUSHES);
    assertEquals(1, sumLong(flushes, Attributes.of(ATTR_FLUSH_OUTCOME, FLUSH_OUTCOME_SUCCEEDED)));
    assertEquals(2, sumLong(flushes, Attributes.of(ATTR_FLUSH_OUTCOME, FLUSH_OUTCOME_FAILED)));
    assertEquals(3, histogramCount(requireMetric(metrics, METRIC_EVENTS_BATCH_SIZE), Attributes.empty()));
    assertEquals(3, histogramCount(requireMetric(metrics, METRIC_EVENTS_FLUSH_DURATION), Attributes.empty()));
  }

  @Test
  public void usesTheGlobalOpenTelemetryByDefault() {
    GlobalOpenTelemetry.resetForTest();
    InMemoryMetricReader reader = InMemoryMetricReader.create();
    try {
      OpenTelemetrySdk.builder()
          .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(reader).build())
          .buildAndRegisterGlobal();
      MetricsHook hook = new MetricsHook();
      hook.eventFlushCompleted(new EventFlushContext(2, 50, true, 202, Duration.ofMillis(1), 0));
      assertEquals(2, sumLong(requireMetric(reader.collectAllMetrics(), METRIC_EVENTS_SENT), Attributes.empty()));
      hook.close();
    } finally {
      GlobalOpenTelemetry.resetForTest();
    }
  }

  @Test
  public void hookHasExpectedMetadata() {
    assertEquals("LaunchDarkly Metrics Hook", new Setup().hook.getMetadata().getName());
    assertEquals(DATA_SYSTEM_NAME, DATA_SYSTEM_DESCRIPTOR.getName());
  }
}
