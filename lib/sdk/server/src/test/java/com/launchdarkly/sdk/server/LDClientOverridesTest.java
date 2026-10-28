package com.launchdarkly.sdk.server;

import com.launchdarkly.logging.LDLogLevel;
import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.EvaluationReason.ErrorKind;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.json.JsonSerialization;
import com.launchdarkly.sdk.server.DataModel.FeatureFlag;
import com.launchdarkly.sdk.server.DataModel.Segment;
import com.launchdarkly.sdk.server.integrations.DataSystemBuilder;
import com.launchdarkly.sdk.server.integrations.FileOverrides;
import com.launchdarkly.sdk.server.integrations.TestDataV2;
import com.launchdarkly.sdk.server.interfaces.FlagChangeEvent;
import com.launchdarkly.sdk.server.interfaces.DataStoreStatusProvider.CacheStats;
import com.launchdarkly.sdk.server.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.server.subsystems.DataStore;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.FullDataSet;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;
import com.launchdarkly.sdk.server.subsystems.DataSystemConfiguration.DataStoreMode;
import com.launchdarkly.sdk.server.subsystems.OverrideSource;
import com.launchdarkly.testhelpers.TempDir;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.launchdarkly.sdk.server.ModelBuilders.clauseMatchingSegment;
import static com.launchdarkly.sdk.server.ModelBuilders.flagBuilder;
import static com.launchdarkly.sdk.server.ModelBuilders.prerequisite;
import static com.launchdarkly.sdk.server.ModelBuilders.ruleBuilder;
import static com.launchdarkly.sdk.server.ModelBuilders.segmentBuilder;
import static com.launchdarkly.sdk.server.OverrideLayerTest.collections;
import static com.launchdarkly.sdk.server.OverrideLayerTest.flagsOnly;
import static com.launchdarkly.sdk.server.OverrideTestDataSources.hangingSynchronizer;
import static com.launchdarkly.sdk.server.OverrideTestDataSources.initializerWith;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The override layer through the client: the not-initialized short-circuit, the all-flags state,
 * flag change notifications, and the override source lifecycle.
 */
@SuppressWarnings("javadoc")
public class LDClientOverridesTest extends BaseTest {
  private static final String SDK_KEY = "sdk-key";
  private static final LDContext CONTEXT = LDContext.create("user-key");
  private static final LDValue DEFAULT = LDValue.of("default");

  private static FeatureFlag valueFlag(String key, String value) {
    return flagBuilder(key).version(1).on(true).fallthroughVariation(0).variations(LDValue.of(value)).build();
  }

  private LDConfig.Builder configWith(DataSystemBuilder dataSystem) {
    return new LDConfig.Builder()
        .dataSystem(dataSystem)
        .events(Components.noEvents())
        .logging(Components.logging(testLogging).level(LDLogLevel.DEBUG));
  }

  private LDConfig initializedConfig(Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> ldData,
      ComponentConfigurer<OverrideSource> source) {
    return configWith(Components.dataSystem().custom().initializers(initializerWith(ldData)).overrides(source)).build();
  }

  private LDConfig uninitializedConfig(ComponentConfigurer<OverrideSource> source) {
    return configWith(Components.dataSystem().custom().synchronizers(hangingSynchronizer()).overrides(source))
        .startWait(Duration.ZERO)
        .build();
  }

  private static void assertOverrideServed(EvaluationDetail<LDValue> detail, String value) {
    assertEquals(LDValue.of(value), detail.getValue());
    assertEquals(0, detail.getVariationIndex());
    assertEquals(EvaluationReason.fallthrough().withOverrideAffected(true), detail.getReason());
  }

  private static void assertNotReady(EvaluationDetail<LDValue> detail) {
    assertEquals(DEFAULT, detail.getValue());
    assertTrue(detail.isDefaultValue());
    assertEquals(EvaluationReason.error(ErrorKind.CLIENT_NOT_READY), detail.getReason());
  }

  @Test
  public void overrideIsServedWhenClientIsNotInitialized() throws Exception {
    TestOverrideSource source = new TestOverrideSource(flagsOnly(valueFlag("overridden", "override-value")));
    try (LDClient client = new LDClient(SDK_KEY, uninitializedConfig(source))) {
      assertFalse(client.isInitialized());
      assertOverrideServed(client.jsonValueVariationDetail("overridden", CONTEXT, DEFAULT), "override-value");
      assertEquals("override-value", client.stringVariation("overridden", CONTEXT, "default"));
    }
  }

  @Test
  public void nonOverriddenFlagStillShortCircuitsWhenClientIsNotInitialized() throws Exception {
    TestOverrideSource source = new TestOverrideSource(flagsOnly(valueFlag("overridden", "override-value")));
    try (LDClient client = new LDClient(SDK_KEY, uninitializedConfig(source))) {
      assertNotReady(client.jsonValueVariationDetail("other", CONTEXT, DEFAULT));
      assertThat(logCapture.getMessageStrings(), hasItem(
          "WARN:Evaluation called before client initialized for feature flag \"other\"; data store unavailable, returning default value"));
    }
  }

  @Test
  public void overrideRemovalRestoresShortCircuit() throws Exception {
    TestOverrideSource source = new TestOverrideSource(flagsOnly(valueFlag("overridden", "override-value")));
    try (LDClient client = new LDClient(SDK_KEY, uninitializedConfig(source))) {
      assertOverrideServed(client.jsonValueVariationDetail("overridden", CONTEXT, DEFAULT), "override-value");
      source.setOverrides(Collections.emptyList());
      assertNotReady(client.jsonValueVariationDetail("overridden", CONTEXT, DEFAULT));
    }
  }

  @Test
  public void overrideTakesPrecedenceOverLaunchDarklyDataAndOtherFlagsAreUnaffected() throws Exception {
    Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> ldData = flagsOnly(
        valueFlag("shared", "ld-value"), valueFlag("plain", "plain-value"));
    TestOverrideSource source = new TestOverrideSource(flagsOnly(valueFlag("shared", "override-value")));
    try (LDClient client = new LDClient(SDK_KEY, initializedConfig(ldData, source))) {
      assertTrue(client.isInitialized());
      assertOverrideServed(client.jsonValueVariationDetail("shared", CONTEXT, DEFAULT), "override-value");
      EvaluationDetail<LDValue> plain = client.jsonValueVariationDetail("plain", CONTEXT, DEFAULT);
      assertEquals(LDValue.of("plain-value"), plain.getValue());
      assertEquals(EvaluationReason.fallthrough(), plain.getReason());
      assertFalse(plain.getReason().isOverrideAffected());

      // Removing the override returns the flag to LaunchDarkly data.
      source.setOverrides(Collections.emptyList());
      EvaluationDetail<LDValue> restored = client.jsonValueVariationDetail("shared", CONTEXT, DEFAULT);
      assertEquals(LDValue.of("ld-value"), restored.getValue());
      assertFalse(restored.getReason().isOverrideAffected());
    }
  }

  @Test
  public void clientWithoutOverrideSourceBehavesAsBefore() throws Exception {
    Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> ldData = flagsOnly(valueFlag("plain", "plain-value"));
    LDConfig config = configWith(Components.dataSystem().custom().initializers(initializerWith(ldData))).build();
    try (LDClient client = new LDClient(SDK_KEY, config)) {
      assertNull(client.dataSystem.getOverrideLayer());
      assertEquals(LDValue.of("plain-value"), client.jsonValueVariation("plain", CONTEXT, DEFAULT));
    }
  }

  @Test
  public void allFlagsStateContainsOnlyOverridesWhenClientIsNotInitialized() throws Exception {
    TestOverrideSource source = new TestOverrideSource(flagsOnly(valueFlag("overridden", "override-value")));
    try (LDClient client = new LDClient(SDK_KEY, uninitializedConfig(source))) {
      FeatureFlagsState state = client.allFlagsState(CONTEXT, FlagsStateOption.WITH_REASONS);
      assertTrue(state.isValid());
      assertEquals(Collections.singletonMap("overridden", LDValue.of("override-value")), state.toValuesMap());
      assertTrue(state.getFlagReason("overridden").isOverrideAffected());
    }
  }

  @Test
  public void clientSideAllFlagsStateKeepsADefinitionWithClientSideButNotAFlagValuesEntry() throws Exception {
    // A flagValues entry is not available to client-side SDKs. A full definition that sets
    // clientSide is, so that is how an operator overrides a flag that client-side SDKs use.
    try (TempDir dir = TempDir.create()) {
      Path file = dir.getPath().resolve("overrides.json");
      Files.write(file, ("{\"flagValues\": {\"value-flag\": true}, \"flags\": {\"full-flag\": {"
          + "\"key\": \"full-flag\", \"version\": 1, \"on\": true, \"variations\": [true], "
          + "\"fallthrough\": {\"variation\": 0}, \"clientSide\": true}}}").getBytes(StandardCharsets.UTF_8));
      try (LDClient client = new LDClient(SDK_KEY, uninitializedConfig(FileOverrides.source().filePaths(file)))) {
        assertEquals(new HashSet<>(Arrays.asList("full-flag", "value-flag")),
            client.allFlagsState(CONTEXT).toValuesMap().keySet());
        FeatureFlagsState clientSide = client.allFlagsState(CONTEXT, FlagsStateOption.CLIENT_SIDE_ONLY);
        assertTrue(clientSide.isValid());
        assertEquals(Collections.singletonMap("full-flag", LDValue.of(true)), clientSide.toValuesMap());
      }
    }
  }

  @Test
  public void allFlagsStateOverridesOnlyWarningIsLoggedOnce() throws Exception {
    TestOverrideSource source = new TestOverrideSource(flagsOnly(valueFlag("overridden", "override-value")));
    try (LDClient client = new LDClient(SDK_KEY, uninitializedConfig(source))) {
      client.allFlagsState(CONTEXT);
      client.allFlagsState(CONTEXT);
      String message = "WARN:allFlagsState() was called before client initialized; returning only flags from the override layer."
          + " This message is logged once.";
      assertEquals(1, logCapture.getMessageStrings().stream().filter(m -> m.equals(message)).count());
    }
  }

  @Test
  public void allFlagsStateIsInvalidWhenNotInitializedAndOverrideLayerIsEmpty() throws Exception {
    TestOverrideSource source = new TestOverrideSource(Collections.emptyList());
    try (LDClient client = new LDClient(SDK_KEY, uninitializedConfig(source))) {
      FeatureFlagsState state = client.allFlagsState(CONTEXT);
      assertFalse(state.isValid());
      assertThat(logCapture.getMessageStrings(), hasItem(
          "WARN:allFlagsState() was called before client initialized; data store unavailable, returning no data"));
    }
  }

  // A client that reads LaunchDarkly data from the given store and never initializes.
  private LDConfig uninitializedConfigWithStore(DataStore store, ComponentConfigurer<OverrideSource> source) {
    return configWith(Components.dataSystem().custom().synchronizers(hangingSynchronizer())
        .persistentStore(context -> store, DataStoreMode.READ_ONLY).overrides(source))
        .startWait(Duration.ZERO)
        .build();
  }

  @Test
  public void allFlagsStateWithoutOverrideFlagsIgnoresDataInAStoreWithoutAnInitMarker() throws Exception {
    // A layer that holds no flags changes nothing: the state is invalid, as it is without a source,
    // and does not report the LaunchDarkly data in the store. That holds for an empty layer and for
    // one that holds only segments.
    FixedDataStore store = new FixedDataStore(flagsOnly(valueFlag("launchdarkly-flag", "ld-value")), false);
    TestOverrideSource emptySource = new TestOverrideSource(Collections.emptyList());
    try (LDClient client = new LDClient(SDK_KEY, uninitializedConfigWithStore(store, emptySource))) {
      FeatureFlagsState state = client.allFlagsState(CONTEXT);
      assertFalse(state.isValid());
      assertEquals(Collections.emptyMap(), state.toValuesMap());
    }
    TestOverrideSource segmentSource = new TestOverrideSource(OverrideLayerTest.collections(
        Collections.<FeatureFlag>emptyList(), Collections.singletonList(segmentBuilder("segment").version(1).build())));
    try (LDClient client = new LDClient(SDK_KEY, uninitializedConfigWithStore(store, segmentSource))) {
      FeatureFlagsState state = client.allFlagsState(CONTEXT);
      assertFalse(state.isValid());
      assertEquals(Collections.emptyMap(), state.toValuesMap());
    }
  }

  @Test
  public void allFlagsStateBeforeInitializationExcludesLaunchDarklyDataFromAStoreWithoutAnInitMarker() throws Exception {
    FixedDataStore store = new FixedDataStore(flagsOnly(valueFlag("launchdarkly-flag", "ld-value")), false);
    TestOverrideSource source = new TestOverrideSource(flagsOnly(valueFlag("overridden", "override-value")));
    try (LDClient client = new LDClient(SDK_KEY, uninitializedConfigWithStore(store, source))) {
      FeatureFlagsState state = client.allFlagsState(CONTEXT);
      assertTrue(state.isValid());
      assertEquals(Collections.singletonMap("overridden", LDValue.of("override-value")), state.toValuesMap());
    }
  }

  @Test
  public void allFlagsStateWhenTheStoreFailsHoldsTheOverridesAndIsInvalid() throws Exception {
    // A daemon-mode client whose store holds an init marker but cannot list its flags logs the
    // failure. Without a source the state is invalid and empty; the overrides add to it, so it holds
    // the overridden flags and stays invalid.
    FixedDataStore store = new FixedDataStore(flagsOnly(), true);
    store.getAllError = new RuntimeException("store failed");
    TestOverrideSource source = new TestOverrideSource(flagsOnly(valueFlag("overridden", "override-value")));
    try (LDClient client = new LDClient(SDK_KEY, daemonConfig(store, source))) {
      FeatureFlagsState state = client.allFlagsState(CONTEXT, FlagsStateOption.WITH_REASONS);
      assertFalse(state.isValid());
      assertEquals(Collections.singletonMap("overridden", LDValue.of("override-value")), state.toValuesMap());
      assertTrue(state.getFlagReason("overridden").isOverrideAffected());
      assertTrue(logCapture.getMessageStrings().stream().anyMatch(m ->
          m.startsWith("ERROR:Exception from data store when evaluating all flags") && m.contains("store failed")));
    }
  }

  @Test
  public void allFlagsStateWhenTheStoreFailsAndTheLayerHoldsNoFlagsIsInvalidAndEmpty() throws Exception {
    FixedDataStore store = new FixedDataStore(flagsOnly(), true);
    store.getAllError = new RuntimeException("store failed");
    try (LDClient client = new LDClient(SDK_KEY, daemonConfig(store, new TestOverrideSource(Collections.emptyList())))) {
      FeatureFlagsState state = client.allFlagsState(CONTEXT);
      assertFalse(state.isValid());
      assertEquals(Collections.emptyMap(), state.toValuesMap());
      assertTrue(logCapture.getMessageStrings().stream().anyMatch(m ->
          m.startsWith("ERROR:Exception from data store when evaluating all flags") && m.contains("store failed")));
    }
  }

  private LDConfig daemonConfig(DataStore store, TestOverrideSource source) {
    return configWith(Components.dataSystem().daemon(context -> store).overrides(source))
        .startWait(Duration.ZERO)
        .build();
  }

  @Test
  public void allFlagsStateTurnsOffEventTrackingForOverrideAffectedFlags() throws Exception {
    long debugUntil = System.currentTimeMillis() + 100000;
    FeatureFlag ldTracked = flagBuilder("tracked-overridden").version(100).on(false).offVariation(0)
        .variations(LDValue.of("ld-value")).trackEvents(true).debugEventsUntilDate(debugUntil).build();
    FeatureFlag ldPlain = flagBuilder("plain").version(100).on(false).offVariation(0)
        .variations(LDValue.of("plain-value")).trackEvents(true).debugEventsUntilDate(debugUntil).build();
    FeatureFlag overrideOnly = flagBuilder("override-only").version(1).on(true).fallthroughVariation(0).offVariation(0)
        .variations(LDValue.of("override-only-value")).trackEvents(true).trackEventsFallthrough(true)
        .debugEventsUntilDate(debugUntil).build();
    TestOverrideSource source = new TestOverrideSource(flagsOnly(
        valueFlag("tracked-overridden", "override-value"), overrideOnly));

    try (LDClient client = new LDClient(SDK_KEY, initializedConfig(flagsOnly(ldTracked, ldPlain), source))) {
      FeatureFlagsState state = client.allFlagsState(CONTEXT, FlagsStateOption.WITH_REASONS);
      LDValue json = LDValue.parse(JsonSerialization.serialize(state));
      LDValue flagsState = json.get("$flagsState");

      // An override-affected flag keeps its value, version, and marked reason, but every tracking
      // field is off.
      assertEquals(LDValue.of("override-value"), json.get("tracked-overridden"));
      LDValue overridden = flagsState.get("tracked-overridden");
      assertEquals(LDValue.of(1), overridden.get("version"));
      assertTrue(overridden.get("reason").get("overrideAffected").booleanValue());
      assertTrue(overridden.get("trackEvents").isNull());
      assertTrue(overridden.get("trackReason").isNull());
      assertTrue(overridden.get("debugEventsUntilDate").isNull());

      // The same holds for a flag that exists only in the override layer, whatever its definition
      // requests, including reason tracking from an experiment-like fallthrough.
      LDValue only = flagsState.get("override-only");
      assertTrue(only.get("trackEvents").isNull());
      assertTrue(only.get("trackReason").isNull());
      assertTrue(only.get("debugEventsUntilDate").isNull());

      // A flag with no override keeps its tracking.
      LDValue plain = flagsState.get("plain");
      assertTrue(plain.get("trackEvents").booleanValue());
      assertEquals(LDValue.of(debugUntil), plain.get("debugEventsUntilDate"));
      assertFalse(plain.get("reason").get("overrideAffected").booleanValue());
    }
  }

  @Test
  public void flagTrackerIsNotifiedOfOverrideChanges() throws Exception {
    // "dependent" depends on "target" in LaunchDarkly data. Overriding "target" affects both.
    FeatureFlag target = valueFlag("target", "ld-value");
    FeatureFlag dependent = flagBuilder("dependent").version(1).on(true).fallthroughVariation(0).offVariation(0)
        .variations(LDValue.of("x")).prerequisites(prerequisite("target", 0)).build();
    FeatureFlag unrelated = valueFlag("unrelated", "u");
    TestOverrideSource source = new TestOverrideSource(Collections.emptyList());

    try (LDClient client = new LDClient(SDK_KEY, initializedConfig(flagsOnly(target, dependent, unrelated), source))) {
      BlockingQueue<FlagChangeEvent> events = new LinkedBlockingQueue<>();
      client.getFlagTracker().addFlagChangeListener(events::add);

      source.setOverrides(flagsOnly(valueFlag("target", "override-value")));
      assertEquals(keys("target", "dependent"), awaitKeys(events, 2));
      assertNull(events.poll(200, TimeUnit.MILLISECONDS));

      source.setOverrides(flagsOnly(valueFlag("target", "override-value-2")));
      assertEquals(keys("target", "dependent"), awaitKeys(events, 2));

      source.setOverrides(Collections.emptyList());
      assertEquals(keys("target", "dependent"), awaitKeys(events, 2));
      assertNull(events.poll(200, TimeUnit.MILLISECONDS));
    }
  }

  @Test
  public void flagValueChangeListenerSeesOverrideChanges() throws Exception {
    TestOverrideSource source = new TestOverrideSource(Collections.emptyList());
    try (LDClient client = new LDClient(SDK_KEY, initializedConfig(flagsOnly(valueFlag("flag", "ld-value")), source))) {
      BlockingQueue<LDValue> values = new LinkedBlockingQueue<>();
      client.getFlagTracker().addFlagValueChangeListener("flag", CONTEXT, e -> values.add(e.getNewValue()));

      source.setOverrides(flagsOnly(valueFlag("flag", "override-value")));
      assertEquals(LDValue.of("override-value"), values.poll(5, TimeUnit.SECONDS));

      source.setOverrides(Collections.emptyList());
      assertEquals(LDValue.of("ld-value"), values.poll(5, TimeUnit.SECONDS));
    }
  }

  @Test
  public void segmentOverrideNotifiesFlagsThatReferenceIt() throws Exception {
    FeatureFlag usesSegment = flagBuilder("uses-segment").version(1).on(true).fallthroughVariation(0).offVariation(0)
        .variations(LDValue.of("no"), LDValue.of("yes"))
        .rules(ruleBuilder().id("r").variation(1).clauses(clauseMatchingSegment("seg")).build()).build();
    Segment ldSegment = segmentBuilder("seg").version(1).build();
    TestOverrideSource source = new TestOverrideSource(Collections.emptyList());

    try (LDClient client = new LDClient(SDK_KEY, initializedConfig(
        collections(Collections.singletonList(usesSegment), Collections.singletonList(ldSegment)), source))) {
      assertEquals(LDValue.of("no"), client.jsonValueVariation("uses-segment", CONTEXT, DEFAULT));
      BlockingQueue<FlagChangeEvent> events = new LinkedBlockingQueue<>();
      client.getFlagTracker().addFlagChangeListener(events::add);

      Segment overrideSegment = segmentBuilder("seg").version(2).included(CONTEXT.getKey()).build();
      source.setOverrides(collections(Collections.<FeatureFlag>emptyList(), Collections.singletonList(overrideSegment)));

      assertEquals(keys("uses-segment"), awaitKeys(events, 1));
      EvaluationDetail<LDValue> detail = client.jsonValueVariationDetail("uses-segment", CONTEXT, DEFAULT);
      assertEquals(LDValue.of("yes"), detail.getValue());
      assertTrue(detail.getReason().isOverrideAffected());
    }
  }

  @Test
  public void overrideSourceIsStartedBeforeConstructorReturnsAndClosedWithClient() throws Exception {
    TestOverrideSource source = new TestOverrideSource(flagsOnly(valueFlag("flag", "override-value")));
    LDClient client = new LDClient(SDK_KEY, initializedConfig(flagsOnly(valueFlag("flag", "ld-value")), source));
    try {
      assertTrue(source.started.get());
      assertFalse(source.closed.get());
      assertEquals(LDValue.of("override-value"), client.jsonValueVariation("flag", CONTEXT, DEFAULT));
    } finally {
      client.close();
    }
    assertTrue(source.closed.get());
  }

  @Test
  public void overrideSourceStartFailureIsLoggedAndTheClientRunsWithoutOverrides() throws Exception {
    // The SDK's own source never throws from start. A custom source could, and that must not keep
    // the client from starting: the failure is logged, the source is closed, and the client runs
    // without overrides.
    TestOverrideSource source = new TestOverrideSource(flagsOnly(valueFlag("flag", "override-value")));
    source.startError = new RuntimeException("source failed");
    try (LDClient client = new LDClient(SDK_KEY, uninitializedConfig(source))) {
      assertTrue(source.closed.get());
      assertTrue(logCapture.getMessageStrings().stream().anyMatch(m ->
          m.startsWith("ERROR:Unable to start the override source; the client runs without overrides")
              && m.contains("source failed")));
      assertNotReady(client.jsonValueVariationDetail("flag", CONTEXT, DEFAULT));
    }
  }

  @Test
  public void overrideSourceThatSuppliesASnapshotAndThenFailsToStartLeavesNoOverrides() throws Exception {
    // The snapshot the source supplied before it failed is removed with the source, so the client
    // runs without overrides, as the log says, and its all-flags state agrees.
    TestOverrideSource source = new TestOverrideSource(flagsOnly(valueFlag("flag", "override-value")));
    source.startError = new RuntimeException("source failed");
    source.supplyDataBeforeStartError = true;
    try (LDClient client = new LDClient(SDK_KEY, uninitializedConfig(source))) {
      assertTrue(source.closed.get());
      assertNotReady(client.jsonValueVariationDetail("flag", CONTEXT, DEFAULT));
      assertFalse(client.allFlagsState(CONTEXT).isValid());
    }
  }

  @Test
  public void overrideSourceCloseFailureDoesNotStopTheClientFromShuttingDown() throws Exception {
    FixedDataStore store = new FixedDataStore(flagsOnly(), false);
    TestOverrideSource source = new TestOverrideSource(Collections.emptyList());
    source.closeError = new RuntimeException("close failed");
    LDConfig config = configWith(Components.dataSystem().custom().synchronizers(hangingSynchronizer())
        .persistentStore(context -> store, DataStoreMode.READ_WRITE).overrides(source))
        .startWait(Duration.ZERO)
        .build();
    LDClient client = new LDClient(SDK_KEY, config);

    client.close();

    assertTrue(source.closed.get());
    assertTrue(store.closed);
    assertTrue(logCapture.getMessageStrings().stream().anyMatch(m ->
        m.startsWith("WARN:Unable to close the override source") && m.contains("close failed")));
  }

  @Test
  public void snapshotSuppliedAfterTheClientIsClosedIsIgnored() throws Exception {
    TestOverrideSource source = new TestOverrideSource(Collections.emptyList());
    LDClient client = new LDClient(SDK_KEY, uninitializedConfig(source));
    client.close();

    source.deliverEvenIfClosed(flagsOnly(valueFlag("late-flag", "override-value")));

    assertNull(client.dataSystem.getOverrideLayer().get(DataModel.FEATURES, "late-flag"));
  }

  @Test
  public void overrideSourceIsBuiltWithClientContext() throws Exception {
    TestOverrideSource source = new TestOverrideSource(Collections.emptyList());
    try (LDClient client = new LDClient(SDK_KEY, initializedConfig(flagsOnly(), source))) {
      assertEquals(SDK_KEY, source.buildContext.getSdkKey());
    }
  }

  @Test
  public void offlineClientDoesNotStartOverrideSource() throws Exception {
    TestOverrideSource source = new TestOverrideSource(flagsOnly(valueFlag("flag", "override-value")));
    LDConfig config = configWith(Components.dataSystem().custom().overrides(source)).offline(true).build();
    try (LDClient client = new LDClient(SDK_KEY, config)) {
      assertFalse(source.started.get());
      assertNull(client.dataSystem.getOverrideLayer());
      assertEquals(DEFAULT, client.jsonValueVariation("flag", CONTEXT, DEFAULT));
      assertThat(logCapture.getMessageStrings(), hasItem(
          "WARN:Offline mode is enabled; the override source is not started and no overrides are served"));
    }
    assertFalse(source.closed.get());
  }

  @Test
  public void invalidOverrideSourceConfigurationFailsClientConstruction() {
    ComponentConfigurer<OverrideSource> broken = context -> {
      throw new IllegalArgumentException("no file paths were specified");
    };
    LDConfig config = configWith(Components.dataSystem().custom().initializers(initializerWith(flagsOnly())).overrides(broken))
        .build();
    try {
      new LDClient(SDK_KEY, config).close();
      fail("expected exception");
    } catch (IllegalArgumentException e) {
      assertEquals("no file paths were specified", e.getMessage());
    } catch (Exception e) {
      fail("unexpected exception " + e);
    }
  }

  @Test
  public void isFlagKnownSeesOverrideOnlyFlagWhenInitialized() throws Exception {
    TestOverrideSource source = new TestOverrideSource(flagsOnly(valueFlag("override-only", "v")));
    try (LDClient client = new LDClient(SDK_KEY, initializedConfig(flagsOnly(valueFlag("plain", "p")), source))) {
      assertTrue(client.isFlagKnown("override-only"));
      assertTrue(client.isFlagKnown("plain"));
      assertFalse(client.isFlagKnown("missing"));
    }
  }

  @Test
  public void overrideLayerDoesNotAffectInitializationStatus() throws Exception {
    TestOverrideSource source = new TestOverrideSource(flagsOnly(valueFlag("flag", "override-value")));
    try (LDClient client = new LDClient(SDK_KEY, uninitializedConfig(source))) {
      assertFalse(client.isInitialized());
      assertThat(client.getDataSourceStatusProvider().getStatus().getState().toString(), not("VALID"));
    }
  }

  private static Set<String> keys(String... keys) {
    Set<String> s = new HashSet<>();
    Collections.addAll(s, keys);
    return s;
  }

  private static Set<String> awaitKeys(BlockingQueue<FlagChangeEvent> events, int count) throws InterruptedException {
    Set<String> keys = new HashSet<>();
    for (int i = 0; i < count; i++) {
      FlagChangeEvent e = events.poll(5, TimeUnit.SECONDS);
      if (e == null) {
        throw new AssertionError("expected " + count + " flag change events but got " + keys);
      }
      keys.add(e.getKey());
    }
    return keys;
  }

  @Test
  public void isFlagKnownReportsAnOverrideBeforeTheClientIsInitialized() throws Exception {
    TestOverrideSource source = new TestOverrideSource(flagsOnly(valueFlag("overridden", "v")));
    try (LDClient client = new LDClient(SDK_KEY, uninitializedConfig(source))) {
      assertFalse(client.isInitialized());
      assertTrue(client.isFlagKnown("overridden"));
      assertFalse(client.isFlagKnown("other"));
    }
  }

  @Test
  public void launchDarklyChangeNotifiesOverridesThatDependOnIt() throws Exception {
    // The override "overridden" has the LaunchDarkly flag "prereq" as a prerequisite. A change to
    // prereq on the LaunchDarkly side notifies the override too.
    TestDataV2 td = TestDataV2.synchronizer();
    td.update(td.flag("prereq").on(true));
    FeatureFlag overridden = flagBuilder("overridden").version(1).on(true).fallthroughVariation(0).offVariation(0)
        .variations(LDValue.of("x")).prerequisites(prerequisite("prereq", 0)).build();
    TestOverrideSource source = new TestOverrideSource(flagsOnly(overridden));
    LDConfig config = configWith(Components.dataSystem().custom().synchronizers(td).overrides(source)).build();
    try (LDClient client = new LDClient(SDK_KEY, config)) {
      BlockingQueue<FlagChangeEvent> events = new LinkedBlockingQueue<>();
      client.getFlagTracker().addFlagChangeListener(events::add);
      td.update(td.flag("prereq").on(false));
      assertEquals(keys("prereq", "overridden"), awaitKeys(events, 2));
      assertNull(events.poll(200, TimeUnit.MILLISECONDS));
    }
  }

  // A store with fixed contents. Constructed not initialized, it is like a persistent store that holds
  // LaunchDarkly data but no init marker. getAll throws getAllError when it is set.
  private static final class FixedDataStore implements DataStore {
    private final Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> data;
    private final boolean initialized;
    volatile RuntimeException getAllError;
    volatile boolean closed;

    FixedDataStore(Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> data, boolean initialized) {
      this.data = data;
      this.initialized = initialized;
    }

    @Override
    public void init(FullDataSet<ItemDescriptor> allData) {
    }

    @Override
    public ItemDescriptor get(DataKind kind, String key) {
      for (Map.Entry<String, ItemDescriptor> e : items(kind)) {
        if (e.getKey().equals(key)) {
          return e.getValue();
        }
      }
      return null;
    }

    @Override
    public KeyedItems<ItemDescriptor> getAll(DataKind kind) {
      if (getAllError != null) {
        throw getAllError;
      }
      return new KeyedItems<>(items(kind));
    }

    @Override
    public boolean upsert(DataKind kind, String key, ItemDescriptor item) {
      return true;
    }

    @Override
    public boolean isInitialized() {
      return initialized;
    }

    @Override
    public boolean isStatusMonitoringEnabled() {
      return false;
    }

    @Override
    public CacheStats getCacheStats() {
      return null;
    }

    @Override
    public void close() {
      closed = true;
    }

    private Iterable<Map.Entry<String, ItemDescriptor>> items(DataKind kind) {
      for (Map.Entry<DataKind, KeyedItems<ItemDescriptor>> e : data) {
        if (e.getKey() == kind) {
          return e.getValue().getItems();
        }
      }
      return Collections.emptyList();
    }
  }
}
