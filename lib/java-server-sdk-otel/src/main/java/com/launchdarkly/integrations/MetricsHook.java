package com.launchdarkly.integrations;

import com.launchdarkly.sdk.server.integrations.DataSourceStatusContext;
import com.launchdarkly.sdk.server.integrations.EventFlushContext;
import com.launchdarkly.sdk.server.integrations.Hook;
import com.launchdarkly.sdk.server.integrations.InitializationContext;
import com.launchdarkly.sdk.server.integrations.InitializerContext;
import com.launchdarkly.sdk.server.integrations.SynchronizerChangeContext;
import com.launchdarkly.sdk.server.interfaces.DataSourceDescriptor;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.BatchCallback;
import io.opentelemetry.api.metrics.DoubleCounter;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;

import java.io.Closeable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A hook that reports SDK operational metrics through the OpenTelemetry metrics API.
 * <p>
 * The hook records analytics event delivery (events delivered, failed, and dropped; flush counts,
 * batch sizes, and durations) and data source health (state, state transitions, errors, time spent
 * interrupted, initializer outcomes, and the active synchronizer). It records nothing per
 * evaluation; use {@link TracingHook} for evaluation telemetry.
 * <p>
 * Data source statuses are attributed to the component that reports them. The hook learns the
 * component from the initializer and synchronizer handlers, not from the status itself. The state
 * gauges are observed at each collection from the hook's view of the data source.
 * <p>
 * One hook instance serves one client. The client closes the hook when it closes, which stops the
 * observations. When no {@link OpenTelemetry} instance is configured, the hook resolves the global
 * one at the first handler invocation, so that an instance registered after the client
 * configuration is built is still used.
 */
public final class MetricsHook extends Hook implements Closeable {
  static final String HOOK_NAME = "LaunchDarkly Metrics Hook";
  static final String INSTRUMENTATION_NAME = "launchdarkly-client";

  static final String METRIC_EVENTS_SENT = "launchdarkly.sdk.events.sent";
  static final String METRIC_EVENTS_FAILED = "launchdarkly.sdk.events.failed";
  static final String METRIC_EVENTS_DROPPED = "launchdarkly.sdk.events.dropped";
  static final String METRIC_EVENTS_FLUSHES = "launchdarkly.sdk.events.flushes";
  static final String METRIC_EVENTS_BATCH_SIZE = "launchdarkly.sdk.events.batch.size";
  static final String METRIC_EVENTS_FLUSH_DURATION = "launchdarkly.sdk.events.flush.duration";
  static final String METRIC_EVENTS_SENT_SIZE = "launchdarkly.sdk.events.sent.size";
  static final String METRIC_DATA_SOURCE_ERRORS = "launchdarkly.sdk.data_source.errors";
  static final String METRIC_DATA_SOURCE_STATE = "launchdarkly.sdk.data_source.state";
  static final String METRIC_DATA_SOURCE_TRANSITIONS = "launchdarkly.sdk.data_source.state.transitions";
  static final String METRIC_DATA_SOURCE_STATE_DURATION = "launchdarkly.sdk.data_source.state.duration";
  static final String METRIC_DATA_SOURCE_INTERRUPTED = "launchdarkly.sdk.data_source.interrupted.time";
  static final String METRIC_DATA_SOURCE_INTERRUPTION = "launchdarkly.sdk.data_source.interruption.duration";
  static final String METRIC_INITIALIZER_ATTEMPTS = "launchdarkly.sdk.data_source.initializer.attempts";
  static final String METRIC_INITIALIZER_DURATION = "launchdarkly.sdk.data_source.initializer.duration";
  static final String METRIC_INITIALIZATION_DURATION = "launchdarkly.sdk.data_source.initialization.duration";
  static final String METRIC_SYNCHRONIZER_TRANSITIONS = "launchdarkly.sdk.data_source.synchronizer.transitions";
  static final String METRIC_SYNCHRONIZER_ACTIVE = "launchdarkly.sdk.data_source.synchronizer.active";

  static final AttributeKey<String> ATTR_FLUSH_OUTCOME = AttributeKey.stringKey("launchdarkly.sdk.events.flush.outcome");
  static final AttributeKey<String> ATTR_DATA_SOURCE_STATE = AttributeKey.stringKey("launchdarkly.sdk.data_source.state");
  static final AttributeKey<String> ATTR_PREVIOUS_STATE = AttributeKey.stringKey("launchdarkly.sdk.data_source.state.previous");
  static final AttributeKey<String> ATTR_ERROR_KIND = AttributeKey.stringKey("launchdarkly.sdk.data_source.error.kind");
  static final AttributeKey<String> ATTR_DATA_SOURCE_NAME = AttributeKey.stringKey("launchdarkly.sdk.data_source.name");
  static final AttributeKey<String> ATTR_DATA_SOURCE_PROTOCOL = AttributeKey.stringKey("launchdarkly.sdk.data_source.protocol");
  static final AttributeKey<String> ATTR_DATA_SOURCE_TRANSPORT = AttributeKey.stringKey("launchdarkly.sdk.data_source.transport");
  static final AttributeKey<String> ATTR_INITIALIZER_OUTCOME = AttributeKey.stringKey("launchdarkly.sdk.data_source.initializer.outcome");
  static final AttributeKey<String> ATTR_INITIALIZATION_OUTCOME = AttributeKey.stringKey("launchdarkly.sdk.data_source.initialization.outcome");
  static final AttributeKey<String> ATTR_SYNCHRONIZER_PREVIOUS = AttributeKey.stringKey("launchdarkly.sdk.data_source.synchronizer.previous");
  static final AttributeKey<String> ATTR_SYNCHRONIZER_CURRENT = AttributeKey.stringKey("launchdarkly.sdk.data_source.synchronizer.current");
  static final AttributeKey<String> ATTR_SYNCHRONIZER_REASON = AttributeKey.stringKey("launchdarkly.sdk.data_source.synchronizer.reason");
  static final AttributeKey<String> ATTR_ERROR_TYPE = AttributeKey.stringKey("error.type");
  static final AttributeKey<Long> ATTR_HTTP_STATUS_CODE = AttributeKey.longKey("http.response.status_code");

  static final String FLUSH_OUTCOME_SUCCEEDED = "succeeded";
  static final String FLUSH_OUTCOME_FAILED = "failed";
  static final String INITIALIZATION_SUCCEEDED = "succeeded";
  static final String INITIALIZATION_FAILED = "failed";
  /** The semantic-convention value for an error that has no more specific type. */
  static final String ERROR_TYPE_OTHER = "_OTHER";
  /** The previous state of the first status, and the name of a synchronizer that does not exist. */
  static final String NONE_VALUE = "none";
  /** Reported for a descriptor field the component did not provide. */
  static final String NOT_PROVIDED_VALUE = "not_provided";
  /** Identifies the data system itself. Statuses reported before any component is known belong to it. */
  static final String DATA_SYSTEM_NAME = "data_system";
  static final DataSourceDescriptor DATA_SYSTEM_DESCRIPTOR = DataSourceDescriptor.named(DATA_SYSTEM_NAME);

  private static final DataSourceStatusProvider.State[] ALL_STATES = DataSourceStatusProvider.State.values();

  /**
   * Builds a {@link MetricsHook}.
   */
  public static final class Builder {
    private OpenTelemetry openTelemetry;
    private Attributes attributes = Attributes.empty();

    private Builder() {
    }

    /**
     * Sets the OpenTelemetry instance whose meter provider the hook uses. By default the hook uses
     * {@link GlobalOpenTelemetry}, resolved at the first handler invocation.
     *
     * @param openTelemetry the instance
     * @return the builder
     */
    public Builder openTelemetry(OpenTelemetry openTelemetry) {
      this.openTelemetry = openTelemetry;
      return this;
    }

    /**
     * Adds constant attributes to every measurement the hook records. Two clients in one process
     * that share a meter provider write to the same series; an attribute such as a client name
     * tells them apart.
     *
     * @param attributes the attributes
     * @return the builder
     */
    public Builder attributes(Attributes attributes) {
      this.attributes = attributes == null ? Attributes.empty() : attributes;
      return this;
    }

    /**
     * @return the hook
     */
    public MetricsHook build() {
      return new MetricsHook(openTelemetry, attributes);
    }
  }

  /**
   * @return a builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Creates a hook that uses {@link GlobalOpenTelemetry}.
   */
  public MetricsHook() {
    this(null, Attributes.empty());
  }

  private MetricsHook(OpenTelemetry openTelemetry, Attributes constantAttributes) {
    super(HOOK_NAME);
    this.configuredOpenTelemetry = openTelemetry;
    this.constantAttributes = constantAttributes;
  }

  private final OpenTelemetry configuredOpenTelemetry;
  private final Attributes constantAttributes;
  private final Object lock = new Object();

  // Created at the first handler invocation; see instruments().
  private Instruments instruments;
  private BatchCallback callback;
  private boolean closed;

  // The hook's view of the data source. Guarded by lock. The observation callback reads a copy.
  // The reporting component: before any initializer or synchronizer is known, the data system itself.
  private DataSourceDescriptor component = DATA_SYSTEM_DESCRIPTOR;
  private Instant componentSince = Instant.EPOCH;
  // Every component that has been the reporting component. Each reads 0 once it stops reporting.
  private final List<DataSourceDescriptor> components = new ArrayList<>();
  private DataSourceStatusProvider.Status status;
  // The reporting component at the time the INTERRUPTED state was entered.
  private DataSourceDescriptor interruptedComponent = DataSourceDescriptor.empty();
  private DataSourceDescriptor activeSynchronizer = DataSourceDescriptor.empty();
  private boolean haveSynchronizer;
  private final List<DataSourceDescriptor> synchronizers = new ArrayList<>();

  private static final class Instruments {
    final LongCounter eventsSent;
    final LongCounter eventsFailed;
    final LongCounter eventsDropped;
    final LongCounter eventsFlushes;
    final LongHistogram eventsBatchSize;
    final DoubleHistogram eventsFlushDuration;
    final LongCounter eventsSentSize;
    final LongCounter dataSourceErrors;
    final LongCounter dataSourceTransitions;
    final DoubleCounter dataSourceInterrupted;
    final DoubleHistogram dataSourceInterruption;
    final LongCounter initializerAttempts;
    final DoubleHistogram initializerDuration;
    final DoubleHistogram initializationDuration;
    final LongCounter synchronizerTransitions;
    final ObservableLongMeasurement dataSourceState;
    final ObservableDoubleMeasurement dataSourceStateDuration;
    final ObservableLongMeasurement synchronizerActive;

    Instruments(Meter meter) {
      eventsSent = meter.counterBuilder(METRIC_EVENTS_SENT)
          .setDescription("Analytics events accepted by the LaunchDarkly events service").setUnit("{event}").build();
      eventsFailed = meter.counterBuilder(METRIC_EVENTS_FAILED)
          .setDescription("Analytics events lost because a batch could not be delivered after all retries")
          .setUnit("{event}").build();
      eventsDropped = meter.counterBuilder(METRIC_EVENTS_DROPPED)
          .setDescription("Analytics events discarded before delivery because the SDK event buffer was full, "
              + "reported with the next flush")
          .setUnit("{event}").build();
      eventsFlushes = meter.counterBuilder(METRIC_EVENTS_FLUSHES)
          .setDescription("Attempts to deliver a batch of analytics events").setUnit("{flush}").build();
      eventsBatchSize = meter.histogramBuilder(METRIC_EVENTS_BATCH_SIZE).ofLongs()
          .setDescription("Number of analytics events in each delivered or failed batch").setUnit("{event}")
          .setExplicitBucketBoundariesAdvice(Arrays.asList(1L, 2L, 5L, 10L, 25L, 50L, 100L, 250L, 500L, 1000L,
              2500L, 5000L, 10000L, 20000L))
          .build();
      eventsFlushDuration = meter.histogramBuilder(METRIC_EVENTS_FLUSH_DURATION)
          .setDescription("Time taken to deliver a batch of analytics events, including any retry").setUnit("s")
          .setExplicitBucketBoundariesAdvice(Arrays.asList(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0,
              10.0, 30.0, 60.0))
          .build();
      eventsSentSize = meter.counterBuilder(METRIC_EVENTS_SENT_SIZE)
          .setDescription("Bytes of analytics event payloads accepted by the LaunchDarkly events service, "
              + "before compression")
          .setUnit("By").build();
      dataSourceErrors = meter.counterBuilder(METRIC_DATA_SOURCE_ERRORS)
          .setDescription("Errors reported by the data source while connecting to or reading from LaunchDarkly")
          .setUnit("{error}").build();
      dataSourceTransitions = meter.counterBuilder(METRIC_DATA_SOURCE_TRANSITIONS)
          .setDescription("Data source state changes").setUnit("{transition}").build();
      dataSourceInterrupted = meter.counterBuilder(METRIC_DATA_SOURCE_INTERRUPTED).ofDoubles()
          .setDescription("Total time spent in completed INTERRUPTED periods").setUnit("s").build();
      dataSourceInterruption = meter.histogramBuilder(METRIC_DATA_SOURCE_INTERRUPTION)
          .setDescription("Duration of each completed INTERRUPTED period").setUnit("s")
          .setExplicitBucketBoundariesAdvice(Arrays.asList(1.0, 5.0, 10.0, 30.0, 60.0, 120.0, 300.0, 600.0, 1800.0,
              3600.0))
          .build();
      initializerAttempts = meter.counterBuilder(METRIC_INITIALIZER_ATTEMPTS)
          .setDescription("Attempts to obtain initial flag data from a data initializer, by outcome")
          .setUnit("{attempt}").build();
      initializerDuration = meter.histogramBuilder(METRIC_INITIALIZER_DURATION)
          .setDescription("Time taken by each data initializer attempt").setUnit("s")
          .setExplicitBucketBoundariesAdvice(Arrays.asList(0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0, 60.0))
          .build();
      initializationDuration = meter.histogramBuilder(METRIC_INITIALIZATION_DURATION)
          .setDescription("Time from data system start until the SDK first had data or gave up").setUnit("s")
          .setExplicitBucketBoundariesAdvice(Arrays.asList(0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0, 60.0,
              120.0))
          .build();
      synchronizerTransitions = meter.counterBuilder(METRIC_SYNCHRONIZER_TRANSITIONS)
          .setDescription("Changes of the active data synchronizer, by reason").setUnit("{transition}").build();
      dataSourceState = meter.gaugeBuilder(METRIC_DATA_SOURCE_STATE).ofLongs()
          .setDescription("1 for the current state of the reporting component, 0 for every other state")
          .setUnit("{state}").buildObserver();
      dataSourceStateDuration = meter.gaugeBuilder(METRIC_DATA_SOURCE_STATE_DURATION)
          .setDescription("Time the reporting component has spent in its current state").setUnit("s")
          .buildObserver();
      synchronizerActive = meter.gaugeBuilder(METRIC_SYNCHRONIZER_ACTIVE).ofLongs()
          .setDescription("1 for the running data synchronizer, 0 for every other synchronizer that has run")
          .setUnit("{synchronizer}").buildObserver();
    }
  }

  /**
   * Returns the instruments, creating them at the first call. Returns null once the hook is closed.
   */
  private Instruments instruments() {
    synchronized (lock) {
      if (instruments == null && !closed) {
        OpenTelemetry openTelemetry = configuredOpenTelemetry != null ? configuredOpenTelemetry
            : GlobalOpenTelemetry.get();
        Meter meter = openTelemetry.getMeter(INSTRUMENTATION_NAME);
        instruments = new Instruments(meter);
        callback = meter.batchCallback(this::observe, instruments.dataSourceState,
            instruments.dataSourceStateDuration, instruments.synchronizerActive);
      }
      return instruments;
    }
  }

  /**
   * Stops the observation of the state gauges. The client calls this when it closes, so that a closed
   * client does not report its last state at each later collection. Safe to call more than once.
   */
  @Override
  public void close() {
    BatchCallback toClose;
    synchronized (lock) {
      closed = true;
      toClose = callback;
      callback = null;
      instruments = null;
    }
    if (toClose != null) {
      toClose.close();
    }
  }

  /**
   * Reports the state gauges from the hook's view of the data source. The reader calls it at each
   * collection on its own thread, so it works from a copy taken under the lock.
   */
  private void observe() {
    Instruments inst;
    DataSourceDescriptor reporting;
    Instant reportingSince;
    List<DataSourceDescriptor> knownComponents;
    DataSourceStatusProvider.Status current;
    DataSourceDescriptor active;
    boolean haveActive;
    List<DataSourceDescriptor> knownSynchronizers;
    synchronized (lock) {
      inst = instruments;
      reporting = component;
      reportingSince = componentSince;
      knownComponents = new ArrayList<>(components);
      current = status;
      active = activeSynchronizer;
      haveActive = haveSynchronizer;
      knownSynchronizers = new ArrayList<>(synchronizers);
    }
    if (inst == null) {
      return;
    }
    Instant now = Instant.now();
    if (current != null) {
      for (DataSourceDescriptor c : knownComponents) {
        for (DataSourceStatusProvider.State state : ALL_STATES) {
          long value = 0;
          double seconds = 0;
          if (c.equals(reporting) && state == current.getState()) {
            value = 1;
            // A component that took over during a state counts its time from the takeover.
            Instant since = later(current.getStateSince(), reportingSince);
            seconds = nonNegativeSeconds(Duration.between(since, now));
          }
          Attributes attrs = sourceAttributes(c).put(ATTR_DATA_SOURCE_STATE, state.name()).build();
          inst.dataSourceState.record(value, attrs);
          inst.dataSourceStateDuration.record(seconds, attrs);
        }
      }
    }
    for (DataSourceDescriptor s : knownSynchronizers) {
      inst.synchronizerActive.record(haveActive && s.equals(active) ? 1 : 0, sourceAttributes(s).build());
    }
  }

  @Override
  public void dataSourceStatusChanged(DataSourceStatusContext statusContext) {
    Instruments inst = instruments();
    if (inst == null) {
      return;
    }
    DataSourceStatusProvider.Status previous = statusContext.getPrevious();
    DataSourceStatusProvider.Status current = statusContext.getCurrent();
    DataSourceStatusProvider.State previousState = previous == null ? null : previous.getState();
    boolean entersInterrupted = current.getState() == DataSourceStatusProvider.State.INTERRUPTED
        && previousState != DataSourceStatusProvider.State.INTERRUPTED;

    DataSourceDescriptor reporting;
    DataSourceDescriptor interrupted;
    synchronized (lock) {
      reporting = component;
      addIfAbsent(components, reporting);
      if (entersInterrupted) {
        interruptedComponent = reporting;
      }
      interrupted = interruptedComponent;
      status = current;
    }

    if (current.getState() != previousState) {
      inst.dataSourceTransitions.add(1, sourceAttributes(reporting)
          .put(ATTR_DATA_SOURCE_STATE, current.getState().name())
          .put(ATTR_PREVIOUS_STATE, previousState == null ? NONE_VALUE : previousState.name())
          .build());
      if (previousState == DataSourceStatusProvider.State.INTERRUPTED) {
        // The interruption belongs to the component that failed, not to one that took over while it lasted.
        if (!interrupted.isDefined()) {
          interrupted = reporting;
        }
        double seconds = nonNegativeSeconds(Duration.between(previous.getStateSince(), current.getStateSince()));
        Attributes attrs = sourceAttributes(interrupted).build();
        inst.dataSourceInterrupted.add(seconds, attrs);
        inst.dataSourceInterruption.record(seconds, attrs);
      }
    }

    DataSourceStatusProvider.ErrorInfo error = current.getLastError();
    if (error != null && !error.equals(previous == null ? null : previous.getLastError())) {
      inst.dataSourceErrors.add(1, errorAttributes(sourceAttributes(reporting), error).build());
    }
  }

  @Override
  public void initializerCompleted(InitializerContext initializerContext) {
    Instruments inst = instruments();
    if (inst == null) {
      return;
    }
    if (initializerContext.isApplied()) {
      // The statuses that follow belong to the initializer whose data was applied.
      synchronized (lock) {
        setComponent(initializerContext.getDataSource());
      }
    }
    Attributes attrs = sourceAttributes(initializerContext.getDataSource())
        .put(ATTR_INITIALIZER_OUTCOME, initializerContext.getOutcome().getValue()).build();
    inst.initializerAttempts.add(1, attrs);
    inst.initializerDuration.record(seconds(initializerContext.getDuration()), attrs);
  }

  @Override
  public void synchronizerChanged(SynchronizerChangeContext changeContext) {
    Instruments inst = instruments();
    if (inst == null) {
      return;
    }
    DataSourceDescriptor current = changeContext.getCurrent();
    synchronized (lock) {
      if (current.isDefined()) {
        setComponent(current);
        activeSynchronizer = current;
        haveSynchronizer = true;
        addIfAbsent(synchronizers, current);
      } else {
        // No synchronizer is left. The last component keeps reporting the statuses that follow.
        haveSynchronizer = false;
      }
    }
    inst.synchronizerTransitions.add(1, baseAttributes()
        .put(ATTR_SYNCHRONIZER_PREVIOUS, synchronizerName(changeContext.getPrevious()))
        .put(ATTR_SYNCHRONIZER_CURRENT, synchronizerName(current))
        .put(ATTR_SYNCHRONIZER_REASON, changeContext.getReason().getValue())
        .build());
  }

  @Override
  public void initializationCompleted(InitializationContext initializationContext) {
    Instruments inst = instruments();
    if (inst == null) {
      return;
    }
    inst.initializationDuration.record(seconds(initializationContext.getDuration()),
        sourceAttributes(initializationContext.getDataSource())
            .put(ATTR_INITIALIZATION_OUTCOME,
                initializationContext.isSucceeded() ? INITIALIZATION_SUCCEEDED : INITIALIZATION_FAILED)
            .build());
  }

  @Override
  public void eventFlushCompleted(EventFlushContext flushContext) {
    Instruments inst = instruments();
    if (inst == null) {
      return;
    }
    long count = flushContext.getEventCount();
    double seconds = seconds(flushContext.getDuration());
    if (flushContext.getDroppedCount() > 0) {
      inst.eventsDropped.add(flushContext.getDroppedCount(), constantAttributes);
    }
    if (flushContext.isSuccess()) {
      Attributes outcome = baseAttributes().put(ATTR_FLUSH_OUTCOME, FLUSH_OUTCOME_SUCCEEDED).build();
      inst.eventsSent.add(count, constantAttributes);
      inst.eventsSentSize.add(flushContext.getPayloadSize(), constantAttributes);
      inst.eventsFlushes.add(1, outcome);
      inst.eventsBatchSize.record(count, outcome);
      inst.eventsFlushDuration.record(seconds, outcome);
      return;
    }
    AttributesBuilder errorAttrs = baseAttributes();
    String errorType = ERROR_TYPE_OTHER;
    if (flushContext.getStatusCode() > 0) {
      errorType = String.valueOf(flushContext.getStatusCode());
      errorAttrs.put(ATTR_HTTP_STATUS_CODE, (long) flushContext.getStatusCode());
    }
    errorAttrs.put(ATTR_ERROR_TYPE, errorType);
    inst.eventsFailed.add(count, errorAttrs.build());
    inst.eventsFlushes.add(1, errorAttrs.put(ATTR_FLUSH_OUTCOME, FLUSH_OUTCOME_FAILED).build());
    Attributes outcome = baseAttributes().put(ATTR_FLUSH_OUTCOME, FLUSH_OUTCOME_FAILED).build();
    inst.eventsBatchSize.record(count, outcome);
    inst.eventsFlushDuration.record(seconds, outcome);
  }

  // Makes d the reporting component. The statuses that follow belong to it. Caller holds lock.
  private void setComponent(DataSourceDescriptor d) {
    if (!d.equals(component)) {
      component = d;
      componentSince = Instant.now();
    }
    addIfAbsent(components, d);
  }

  private static void addIfAbsent(List<DataSourceDescriptor> list, DataSourceDescriptor d) {
    if (!list.contains(d)) {
      list.add(d);
    }
  }

  private AttributesBuilder baseAttributes() {
    return Attributes.builder().putAll(constantAttributes);
  }

  // The attributes that identify a data source component. Fields the component did not provide are
  // reported as "not_provided" so that series carry a consistent key set.
  private AttributesBuilder sourceAttributes(DataSourceDescriptor d) {
    return baseAttributes()
        .put(ATTR_DATA_SOURCE_NAME, orNotProvided(d.getName()))
        .put(ATTR_DATA_SOURCE_PROTOCOL, d.getProtocol() == null ? NOT_PROVIDED_VALUE : d.getProtocol().getValue())
        .put(ATTR_DATA_SOURCE_TRANSPORT, d.getTransport() == null ? NOT_PROVIDED_VALUE : d.getTransport().getValue());
  }

  private static AttributesBuilder errorAttributes(AttributesBuilder builder, DataSourceStatusProvider.ErrorInfo e) {
    builder.put(ATTR_ERROR_KIND, e.getKind().name());
    if (e.getStatusCode() > 0) {
      builder.put(ATTR_HTTP_STATUS_CODE, (long) e.getStatusCode());
      builder.put(ATTR_ERROR_TYPE, String.valueOf(e.getStatusCode()));
    } else {
      builder.put(ATTR_ERROR_TYPE, e.getKind().name());
    }
    return builder;
  }

  private static String orNotProvided(String value) {
    return value == null || value.isEmpty() ? NOT_PROVIDED_VALUE : value;
  }

  // The name attribute value for one side of a synchronizer change.
  private static String synchronizerName(DataSourceDescriptor d) {
    if (d == null || !d.isDefined()) {
      return NONE_VALUE;
    }
    return orNotProvided(d.getName());
  }

  private static Instant later(Instant a, Instant b) {
    return b.isAfter(a) ? b : a;
  }

  private static double seconds(Duration d) {
    return d == null ? 0 : d.toNanos() / 1_000_000_000.0;
  }

  // A clock that steps backwards can produce a negative time, which a counter does not accept.
  private static double nonNegativeSeconds(Duration d) {
    return d.isNegative() ? 0 : seconds(d);
  }
}
