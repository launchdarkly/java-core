package com.launchdarkly.sdk.server.ai;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.FeatureFlagsState;
import com.launchdarkly.sdk.server.FlagsStateOption;
import com.launchdarkly.sdk.server.MigrationOpTracker;
import com.launchdarkly.sdk.server.MigrationStage;
import com.launchdarkly.sdk.server.MigrationVariation;
import com.launchdarkly.sdk.server.interfaces.BigSegmentStoreStatusProvider;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;
import com.launchdarkly.sdk.server.interfaces.DataStoreStatusProvider;
import com.launchdarkly.sdk.server.interfaces.FlagTracker;
import com.launchdarkly.sdk.server.interfaces.LDClientInterface;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A test double for {@link LDClientInterface} that returns programmed JSON flag variations and
 * records all {@code track} calls. Methods not exercised by the AI SDK return harmless defaults.
 */
public final class MockLDClient implements LDClientInterface {
  /** A single recorded track call. */
  public static final class TrackEvent {
    public final String eventName;
    public final LDContext context;
    public final LDValue data;
    public final Double metricValue;

    TrackEvent(String eventName, LDContext context, LDValue data, Double metricValue) {
      this.eventName = eventName;
      this.context = context;
      this.data = data;
      this.metricValue = metricValue;
    }
  }

  private final Map<String, LDValue> flags = new HashMap<>();
  public final List<TrackEvent> events = new ArrayList<>();

  /** Programs a JSON variation for a flag key. */
  public MockLDClient setFlag(String key, LDValue value) {
    flags.put(key, value);
    return this;
  }

  public List<TrackEvent> eventsNamed(String eventName) {
    List<TrackEvent> matching = new ArrayList<>();
    for (TrackEvent event : events) {
      if (event.eventName.equals(eventName)) {
        matching.add(event);
      }
    }
    return matching;
  }

  @Override
  public LDValue jsonValueVariation(String key, LDContext context, LDValue defaultValue) {
    return flags.containsKey(key) ? flags.get(key) : defaultValue;
  }

  @Override
  public void track(String eventName, LDContext context) {
    events.add(new TrackEvent(eventName, context, LDValue.ofNull(), null));
  }

  @Override
  public void trackData(String eventName, LDContext context, LDValue data) {
    events.add(new TrackEvent(eventName, context, data, null));
  }

  @Override
  public void trackMetric(String eventName, LDContext context, LDValue data, double metricValue) {
    events.add(new TrackEvent(eventName, context, data, metricValue));
  }

  @Override
  public LDLogger getLogger() {
    return LDLogger.none();
  }

  // --- Methods below are not exercised by the AI SDK; they return harmless defaults. ---

  @Override
  public boolean isInitialized() {
    return true;
  }

  @Override
  public void trackMigration(MigrationOpTracker tracker) {
  }

  @Override
  public void identify(LDContext context) {
  }

  @Override
  public FeatureFlagsState allFlagsState(LDContext context, FlagsStateOption... options) {
    return null;
  }

  @Override
  public boolean boolVariation(String key, LDContext context, boolean defaultValue) {
    return defaultValue;
  }

  @Override
  public int intVariation(String key, LDContext context, int defaultValue) {
    return defaultValue;
  }

  @Override
  public double doubleVariation(String key, LDContext context, double defaultValue) {
    return defaultValue;
  }

  @Override
  public String stringVariation(String key, LDContext context, String defaultValue) {
    return defaultValue;
  }

  @Override
  public EvaluationDetail<Boolean> boolVariationDetail(String key, LDContext context, boolean defaultValue) {
    return EvaluationDetail.fromValue(defaultValue, 0, null);
  }

  @Override
  public EvaluationDetail<Integer> intVariationDetail(String key, LDContext context, int defaultValue) {
    return EvaluationDetail.fromValue(defaultValue, 0, null);
  }

  @Override
  public EvaluationDetail<Double> doubleVariationDetail(String key, LDContext context, double defaultValue) {
    return EvaluationDetail.fromValue(defaultValue, 0, null);
  }

  @Override
  public EvaluationDetail<String> stringVariationDetail(String key, LDContext context, String defaultValue) {
    return EvaluationDetail.fromValue(defaultValue, 0, null);
  }

  @Override
  public EvaluationDetail<LDValue> jsonValueVariationDetail(String key, LDContext context, LDValue defaultValue) {
    return EvaluationDetail.fromValue(jsonValueVariation(key, context, defaultValue), 0, null);
  }

  @Override
  public MigrationVariation migrationVariation(String key, LDContext context, MigrationStage defaultStage) {
    return null;
  }

  @Override
  public boolean isFlagKnown(String featureKey) {
    return flags.containsKey(featureKey);
  }

  @Override
  public void close() {
  }

  @Override
  public void flush() {
  }

  @Override
  public boolean isOffline() {
    return false;
  }

  @Override
  public FlagTracker getFlagTracker() {
    return null;
  }

  @Override
  public BigSegmentStoreStatusProvider getBigSegmentStoreStatusProvider() {
    return null;
  }

  @Override
  public DataSourceStatusProvider getDataSourceStatusProvider() {
    return null;
  }

  @Override
  public DataStoreStatusProvider getDataStoreStatusProvider() {
    return null;
  }

  @Override
  public String secureModeHash(LDContext context) {
    return null;
  }

  @Override
  public String version() {
    return "test";
  }
}
