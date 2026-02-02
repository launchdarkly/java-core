package com.launchdarkly.sdk.server.integrations;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.launchdarkly.sdk.ContextKind;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.DataModel;
import com.launchdarkly.sdk.server.LDConfig;
import com.launchdarkly.sdk.server.ModelBuilders;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider.ErrorInfo;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider.State;
import com.launchdarkly.sdk.server.interfaces.DataStoreStatusProvider;
import com.launchdarkly.sdk.server.subsystems.DataSource;
import com.launchdarkly.sdk.server.subsystems.DataSourceUpdateSink;
import com.launchdarkly.sdk.server.subsystems.DataSourceUpdateSinkV2;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ChangeSet;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ChangeSetType;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.FullDataSet;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.launchdarkly.sdk.server.ModelBuilders.flagBuilder;
import static com.launchdarkly.sdk.server.TestComponents.clientContext;
import static com.launchdarkly.testhelpers.JsonAssertions.assertJsonEquals;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.iterableWithSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@SuppressWarnings("javadoc")
public class TestDataTest {
  private static final LDValue[] THREE_STRING_VALUES =
      new LDValue[] { LDValue.of("red"), LDValue.of("green"), LDValue.of("blue") };
  
  private static final int START_TIMEOUT_SECONDS = 5; // necessary due to synchronizer to data source adapter using thread

  private CapturingDataSourceUpdates updates = new CapturingDataSourceUpdates();

  // Test implementation note: We're using the ModelBuilders test helpers to build the expected
  // flag JSON. However, we have to use them in a slightly different way than we do in other tests
  // (for instance, writing out an expected clause as a JSON literal), because specific data model
  // classes like FeatureFlag and Clause aren't visible from the integrations package. 
  
  @Test
  public void initializesWithEmptyData() throws Exception {
    TestData td = TestData.dataSource();
    DataSource ds = td.build(clientContext("", new LDConfig.Builder().build(), updates));
    Future<Void> started = ds.start();
    started.get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);

    assertThat(updates.valid, is(true));
    assertThat(updates.applies.size(), equalTo(1));
    ChangeSet<ItemDescriptor> changeSet = updates.applies.take();
    assertThat(changeSet.getType(), equalTo(ChangeSetType.Full));
    assertThat(changeSet.getData(), iterableWithSize(1));
    assertThat(Iterables.get(changeSet.getData(), 0).getKey(), equalTo(DataModel.FEATURES));
    Map<String, ItemDescriptor> flags = getFlagsFromChangeSet(changeSet);
    assertThat(flags.isEmpty(), is(true));
  }

  @Test
  public void initializesWithFlags() throws Exception {
    TestData td = TestData.dataSource();
    
    td.update(td.flag("flag1").on(true))
      .update(td.flag("flag2").on(false));
    
    DataSource ds = td.build(clientContext("", new LDConfig.Builder().build(), updates));
    Future<Void> started = ds.start();
    started.get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);

    assertThat(updates.valid, is(true));
    assertThat(updates.applies.size(), equalTo(1));
    ChangeSet<ItemDescriptor> changeSet = updates.applies.take();
    assertThat(changeSet.getType(), equalTo(ChangeSetType.Full));
    assertThat(changeSet.getData(), iterableWithSize(1));
    assertThat(Iterables.get(changeSet.getData(), 0).getKey(), equalTo(DataModel.FEATURES));
    Map<String, ItemDescriptor> flags = getFlagsFromChangeSet(changeSet);
    assertThat(flags.entrySet(), iterableWithSize(2));
    
    ModelBuilders.FlagBuilder expectedFlag1 = flagBuilder("flag1").version(1).salt("")
        .on(true).offVariation(1).fallthroughVariation(0).variations(true, false);
    ModelBuilders.FlagBuilder expectedFlag2 = flagBuilder("flag2").version(1).salt("")
        .on(false).offVariation(1).fallthroughVariation(0).variations(true, false);

    ItemDescriptor flag1 = flags.get("flag1");
    ItemDescriptor flag2 = flags.get("flag2");
    assertThat(flag1, not(nullValue()));
    assertThat(flag2, not(nullValue()));
    
    assertJsonEquals(flagJson(expectedFlag1, 1), flagJson(flag1));
    assertJsonEquals(flagJson(expectedFlag2, 1), flagJson(flag2));
  }
  
  @Test
  public void addsFlag() throws Exception {
    TestData td = TestData.dataSource();
    DataSource ds = td.build(clientContext("", new LDConfig.Builder().build(), updates));
    Future<Void> started = ds.start();
    started.get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);

    assertThat(updates.valid, is(true));
    td.update(td.flag("flag1").on(true));

    ModelBuilders.FlagBuilder expectedFlag = flagBuilder("flag1").version(1).salt("")
        .on(true).offVariation(1).fallthroughVariation(0).variations(true, false);

    // First apply is initial full (empty); second is the update with flag1
    updates.applies.take();
    ChangeSet<ItemDescriptor> changeSet = updates.applies.poll(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(changeSet, notNullValue());
    Map<String, ItemDescriptor> flags = getFlagsFromChangeSet(changeSet);
    ItemDescriptor flag1 = flags.get("flag1");
    assertThat(flag1, not(nullValue()));
    assertJsonEquals(flagJson(expectedFlag, 1), flagJson(flag1));
  }

  @Test
  public void updatesFlag() throws Exception {
    TestData td = TestData.dataSource();
    td.update(td.flag("flag1")
      .on(false)
      .variationForUser("a", true)
      .ifMatch("name", LDValue.of("Lucy")).thenReturn(true));
      // Here we're verifying that the original targets & rules are copied over if we didn't change them

    ModelBuilders.FlagBuilder expectedFlag = flagBuilder("flag1").version(1).salt("")
        .on(false).offVariation(1).fallthroughVariation(0).variations(true, false)
        .addTarget(0, "a").addContextTarget(ContextKind.DEFAULT, 0)
        .addRule("rule0", 0, "{\"contextKind\":\"user\",\"attribute\":\"name\",\"op\":\"in\",\"values\":[\"Lucy\"]}");

    DataSource ds = td.build(clientContext("", new LDConfig.Builder().build(), updates));
    Future<Void> started = ds.start();
    started.get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);

    assertThat(updates.valid, is(true));
    td.update(td.flag("flag1").on(true));

    // First apply is initial full (flag1 v1); second is the update to flag1 v2
    updates.applies.take();
    ChangeSet<ItemDescriptor> changeSet = updates.applies.poll(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(changeSet, notNullValue());
    Map<String, ItemDescriptor> flags = getFlagsFromChangeSet(changeSet);
    ItemDescriptor flag1 = flags.get("flag1");
    assertThat(flag1, not(nullValue()));
    expectedFlag.on(true).version(2);
    assertJsonEquals(flagJson(expectedFlag, 2), flagJson(flag1));
  }

  @Test
  public void deletesFlag() throws Exception {
    final TestData td = TestData.dataSource();

    try (final DataSource ds = td.build(clientContext("", new LDConfig.Builder().build(), updates))) {
      final Future<Void> started = ds.start();
      started.get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat(updates.valid, is(true));

      td.update(td.flag("foo").on(false).valueForAll(LDValue.of("bar")));
      td.delete("foo");

      // First apply is initial full (empty); second is update with foo v1; third is delete (foo v2 tombstone)
      updates.applies.take();
      ChangeSet<ItemDescriptor> addSet = updates.applies.poll(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat(addSet, notNullValue());
      Map<String, ItemDescriptor> addFlags = getFlagsFromChangeSet(addSet);
      ItemDescriptor fooV1 = addFlags.get("foo");
      assertThat(fooV1, not(nullValue()));
      assertThat(fooV1.getVersion(), equalTo(1));
      assertThat(fooV1.getItem(), notNullValue());

      ChangeSet<ItemDescriptor> deleteSet = updates.applies.poll(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat(deleteSet, notNullValue());
      Map<String, ItemDescriptor> deleteFlags = getFlagsFromChangeSet(deleteSet);
      ItemDescriptor fooV2 = deleteFlags.get("foo");
      assertThat(fooV2, not(nullValue()));
      assertThat(fooV2.getVersion(), equalTo(2));
      assertThat(fooV2.getItem(), nullValue());
    }
  }

  @Test
  public void flagConfigSimpleBoolean() throws Exception {
    Function<ModelBuilders.FlagBuilder, ModelBuilders.FlagBuilder> expectedBooleanFlag = fb ->
      fb.on(true).variations(true, false).offVariation(1).fallthroughVariation(0);

    verifyFlag(f -> f, expectedBooleanFlag);
    verifyFlag(f -> f.booleanFlag(), expectedBooleanFlag); // already the default
    verifyFlag(f -> f.on(true), expectedBooleanFlag);      // already the default
    verifyFlag(f -> f.on(false), fb -> expectedBooleanFlag.apply(fb).on(false));
    verifyFlag(f -> f.variationForAll(false), fb -> expectedBooleanFlag.apply(fb).fallthroughVariation(1));
    verifyFlag(f -> f.variationForAll(true), expectedBooleanFlag); // already the default
    verifyFlag(f -> f.fallthroughVariation(true).offVariation(false), expectedBooleanFlag); // already the default
    
    verifyFlag(
        f -> f.fallthroughVariation(false).offVariation(true),
        fb -> expectedBooleanFlag.apply(fb).fallthroughVariation(1).offVariation(0)
        );
  }

  @Test
  public void usingBooleanConfigMethodsForcesFlagToBeBoolean() throws Exception {
    Function<ModelBuilders.FlagBuilder, ModelBuilders.FlagBuilder> expectedBooleanFlag = fb ->
      fb.on(true).variations(true, false).offVariation(1).fallthroughVariation(0);

      verifyFlag(
          f -> f.variations(LDValue.of(1), LDValue.of(2)).booleanFlag(),
          expectedBooleanFlag
          );
      verifyFlag(
          f -> f.variations(LDValue.of(true), LDValue.of(2)).booleanFlag(),
          expectedBooleanFlag
          );
      verifyFlag(
          f -> f.booleanFlag(),
          expectedBooleanFlag
          );
  }
  
  @Test
  public void flagConfigStringVariations() throws Exception {
    verifyFlag(
        f -> f.variations(THREE_STRING_VALUES).offVariation(0).fallthroughVariation(2),
        fb -> fb.variations("red", "green", "blue").on(true).offVariation(0).fallthroughVariation(2)
        );
  }

  @Test
  public void flagConfigSamplingRatio() throws Exception {
    verifyFlag(
        f -> f.samplingRatio(2).on(false),
        fb -> fb.samplingRatio(2).fallthroughVariation(0).variations(true,false).offVariation(1)
    );
  }

  @Test
  public void flagConfigMigrationCheckRatio() throws Exception {
    verifyFlag(
        f -> f.migrationCheckRatio(2).on(false),
        fb -> fb.migration(new ModelBuilders.MigrationBuilder().checkRatio(2).build())
            .fallthroughVariation(0).variations(true,false).offVariation(1)
    );
  }

  @Test
  public void userTargets() throws Exception {
    Function<ModelBuilders.FlagBuilder, ModelBuilders.FlagBuilder> expectedBooleanFlag = fb ->
        fb.variations(true, false).on(true).offVariation(1).fallthroughVariation(0);

    verifyFlag(
        f -> f.variationForUser("a", true).variationForUser("b", true),
        fb -> expectedBooleanFlag.apply(fb).addTarget(0, "a", "b")
          .addContextTarget(ContextKind.DEFAULT, 0)
        );
    verifyFlag(
        f -> f.variationForUser("a", true).variationForUser("a", true),
        fb -> expectedBooleanFlag.apply(fb).addTarget(0, "a")
          .addContextTarget(ContextKind.DEFAULT, 0)
        );
    verifyFlag(
        f -> f.variationForUser("a", true).variationForUser("a", false),
        fb -> expectedBooleanFlag.apply(fb).addTarget(1, "a")
          .addContextTarget(ContextKind.DEFAULT, 1)
        );
    verifyFlag(
        f -> f.variationForUser("a", false).variationForUser("b", true).variationForUser("c", false),
        fb -> expectedBooleanFlag.apply(fb).addTarget(0, "b").addTarget(1, "a", "c")
          .addContextTarget(ContextKind.DEFAULT, 0).addContextTarget(ContextKind.DEFAULT, 1)
        );
    verifyFlag(
        f -> f.variationForUser("a", true).variationForUser("b", true).variationForUser("a", false),
        fb -> expectedBooleanFlag.apply(fb).addTarget(0, "b").addTarget(1, "a")
          .addContextTarget(ContextKind.DEFAULT, 0).addContextTarget(ContextKind.DEFAULT, 1)
        );
    
    Function<ModelBuilders.FlagBuilder, ModelBuilders.FlagBuilder> expectedStringFlag = fb ->
        fb.variations("red", "green", "blue").on(true).offVariation(0).fallthroughVariation(2);
    
    verifyFlag(
        f -> f.variations(THREE_STRING_VALUES).offVariation(0).fallthroughVariation(2)
          .variationForUser("a", 2).variationForUser("b", 2),
        fb -> expectedStringFlag.apply(fb).addTarget(2, "a", "b")
          .addContextTarget(ContextKind.DEFAULT, 2)
        );
    verifyFlag(
        f -> f.variations(THREE_STRING_VALUES).offVariation(0).fallthroughVariation(2)
          .variationForUser("a", 2).variationForUser("b", 1).variationForUser("c", 2),
        fb -> expectedStringFlag.apply(fb).addTarget(1, "b").addTarget(2, "a", "c")
          .addContextTarget(ContextKind.DEFAULT, 1).addContextTarget(ContextKind.DEFAULT, 2)
        );
    
    // clear previously set targets
    verifyFlag(
        f -> f.variationForUser("a", true).clearTargets(),
        expectedBooleanFlag
        );
  }
  
  @Test
  public void contextTargets() throws Exception {
    ContextKind kind1 = ContextKind.of("org"), kind2 = ContextKind.of("other");

    Function<ModelBuilders.FlagBuilder, ModelBuilders.FlagBuilder> expectedBooleanFlag = fb ->
        fb.variations(true, false).on(true).offVariation(1).fallthroughVariation(0);

    verifyFlag(
        f -> f.variationForKey(kind1, "a", true).variationForKey(kind1, "b", true),
        fb -> expectedBooleanFlag.apply(fb).addContextTarget(kind1, 0, "a", "b")
        );
    verifyFlag(
        f -> f.variationForKey(kind1, "a", true).variationForKey(kind2, "a", true),
        fb -> expectedBooleanFlag.apply(fb).addContextTarget(kind1, 0, "a").addContextTarget(kind2, 0, "a")
        );
    verifyFlag(
        f -> f.variationForKey(kind1, "a", true).variationForKey(kind1, "a", true),
        fb -> expectedBooleanFlag.apply(fb).addContextTarget(kind1, 0, "a")
        );
    verifyFlag(
        f -> f.variationForKey(kind1, "a", true).variationForKey(kind1, "a", false),
        fb -> expectedBooleanFlag.apply(fb).addContextTarget(kind1, 1, "a")
        );

    Function<ModelBuilders.FlagBuilder, ModelBuilders.FlagBuilder> expectedStringFlag = fb ->
        fb.variations("red", "green", "blue").on(true).offVariation(0).fallthroughVariation(2);

    verifyFlag(
        f -> f.variations(THREE_STRING_VALUES).offVariation(0).fallthroughVariation(2)
          .variationForKey(kind1, "a", 2).variationForKey(kind1, "b", 2),
        fb -> expectedStringFlag.apply(fb).addContextTarget(kind1, 2, "a", "b")
        );
    
    // clear previously set targets
    verifyFlag(
        f -> f.variationForKey(kind1, "a", true).clearTargets(),
        expectedBooleanFlag
        );
  }
  
  @Test
  public void flagRules() throws Exception {
    Function<ModelBuilders.FlagBuilder, ModelBuilders.FlagBuilder> expectedBooleanFlag = fb ->
      fb.variations(true, false).on(true).offVariation(1).fallthroughVariation(0);

    // match that returns variation 0/true
    Function<ModelBuilders.FlagBuilder, ModelBuilders.FlagBuilder> matchReturnsVariation0 = fb ->
        expectedBooleanFlag.apply(fb).addRule("rule0", 0, 
            "{\"contextKind\":\"user\",\"attribute\":\"name\",\"op\":\"in\",\"values\":[\"Lucy\"]}");
        
    verifyFlag(
        f -> f.ifMatch("name", LDValue.of("Lucy")).thenReturn(true),
        matchReturnsVariation0
        );
    verifyFlag(
        f -> f.ifMatch("name", LDValue.of("Lucy")).thenReturn(0),
        matchReturnsVariation0
        );
    
    // match that returns variation 1/false
    Function<ModelBuilders.FlagBuilder, ModelBuilders.FlagBuilder> matchReturnsVariation1 = fb ->
        expectedBooleanFlag.apply(fb).addRule("rule0", 1, 
            "{\"contextKind\":\"user\",\"attribute\":\"name\",\"op\":\"in\",\"values\":[\"Lucy\"]}");
   
    verifyFlag(
        f -> f.ifMatch("name", LDValue.of("Lucy")).thenReturn(false),
        matchReturnsVariation1
        );
    verifyFlag(
        f -> f.ifMatch("name", LDValue.of("Lucy")).thenReturn(1),
        matchReturnsVariation1
        );
    
    // negated match
    verifyFlag(
        f -> f.ifNotMatch("name", LDValue.of("Lucy")).thenReturn(true),
        fb -> expectedBooleanFlag.apply(fb).addRule("rule0", 0, 
            "{\"contextKind\":\"user\",\"attribute\":\"name\",\"op\":\"in\",\"values\":[\"Lucy\"],\"negate\":true}")
        );

    // context kinds
    verifyFlag(
        f -> f.ifMatch(ContextKind.of("org"), "name", LDValue.of("Catco")).thenReturn(true),
        fb -> expectedBooleanFlag.apply(fb).addRule("rule0", 0, 
            "{\"contextKind\":\"org\",\"attribute\":\"name\",\"op\":\"in\",\"values\":[\"Catco\"]}")
        );
    verifyFlag(
        f -> f.ifNotMatch(ContextKind.of("org"), "name", LDValue.of("Catco")).thenReturn(true),
        fb -> expectedBooleanFlag.apply(fb).addRule("rule0", 0, 
            "{\"contextKind\":\"org\",\"attribute\":\"name\",\"op\":\"in\",\"values\":[\"Catco\"],\"negate\":true}")
        );
    
    // multiple clauses
    verifyFlag(
        f -> f.ifMatch("name", LDValue.of("Lucy"))
          .andMatch("country", LDValue.of("gb"))
          .thenReturn(true),
        fb -> expectedBooleanFlag.apply(fb).addRule("rule0", 0, 
            "{\"contextKind\":\"user\",\"attribute\":\"name\",\"op\":\"in\",\"values\":[\"Lucy\"]}",
            "{\"contextKind\":\"user\",\"attribute\":\"country\",\"op\":\"in\",\"values\":[\"gb\"]}")
        );
    verifyFlag(
        f -> f.ifMatch("name", LDValue.of("Lucy"))
          .andMatch("country", LDValue.of("gb"))
          .thenReturn(true),
        fb -> expectedBooleanFlag.apply(fb).addRule("rule0", 0, 
            "{\"contextKind\":\"user\",\"attribute\":\"name\",\"op\":\"in\",\"values\":[\"Lucy\"]}",
            "{\"contextKind\":\"user\",\"attribute\":\"country\",\"op\":\"in\",\"values\":[\"gb\"]}")
        );
    
    // multiple rules
    verifyFlag(
        f -> f.ifMatch("name", LDValue.of("Lucy")).thenReturn(true)
          .ifMatch("name", LDValue.of("Mina")).thenReturn(false),
        fb -> expectedBooleanFlag.apply(fb)
          .addRule("rule0", 0, "{\"contextKind\":\"user\",\"attribute\":\"name\",\"op\":\"in\",\"values\":[\"Lucy\"]}")
          .addRule("rule1", 1, "{\"contextKind\":\"user\",\"attribute\":\"name\",\"op\":\"in\",\"values\":[\"Mina\"]}")
        );
    
    // clear previously set rules
    verifyFlag(
        f -> f.ifMatch("name", LDValue.of("Lucy")).thenReturn(true).clearRules(),
        expectedBooleanFlag
        );
  }

  private void verifyFlag(
      Function<TestData.FlagBuilder, TestData.FlagBuilder> configureFlag,
      Function<ModelBuilders.FlagBuilder, ModelBuilders.FlagBuilder> configureExpectedFlag
      ) throws Exception {
    ModelBuilders.FlagBuilder expectedFlag = flagBuilder("flagkey").version(1).salt("");
    expectedFlag = configureExpectedFlag.apply(expectedFlag);

    TestData td = TestData.dataSource();

    DataSource ds = td.build(clientContext("", new LDConfig.Builder().build(), updates));
    Future<Void> started = ds.start();
    started.get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);

    td.update(configureFlag.apply(td.flag("flagkey")));

    // First apply is initial full (empty); second is the update with the flag
    updates.applies.take();
    ChangeSet<ItemDescriptor> changeSet = updates.applies.poll(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(changeSet, notNullValue());
    Map<String, ItemDescriptor> flags = getFlagsFromChangeSet(changeSet);
    ItemDescriptor flag = flags.get("flagkey");
    assertThat(flag, not(nullValue()));
    assertJsonEquals(flagJson(expectedFlag, 1), flagJson(flag));
  }

  private static String flagJson(ModelBuilders.FlagBuilder flagBuilder, int version) {
    return DataModel.FEATURES.serialize(new ItemDescriptor(version, flagBuilder.build()));
  }
  
  private static String flagJson(ItemDescriptor flag) {
    return DataModel.FEATURES.serialize(flag);
  }

  /** Extracts the feature-flag key-to-descriptor map from a change set (Full or Partial). */
  private static Map<String, ItemDescriptor> getFlagsFromChangeSet(ChangeSet<ItemDescriptor> changeSet) {
    Map<String, ItemDescriptor> flags = new HashMap<>();
    for (Map.Entry<DataKind, KeyedItems<ItemDescriptor>> entry : changeSet.getData()) {
      if (entry.getKey().equals(DataModel.FEATURES)) {
        for (Map.Entry<String, ItemDescriptor> item : entry.getValue().getItems()) {
          flags.put(item.getKey(), item.getValue());
        }
        break;
      }
    }
    return flags;
  }

  private static class UpsertParams {
    final DataKind kind;
    final String key;
    final ItemDescriptor item;
    
    UpsertParams(DataKind kind, String key, ItemDescriptor item) {
      this.kind = kind;
      this.key = key;
      this.item = item;
    }
  }
  
  private static class CapturingDataSourceUpdates implements DataSourceUpdateSink, DataSourceUpdateSinkV2 {
    BlockingQueue<FullDataSet<ItemDescriptor>> inits = new LinkedBlockingQueue<>();
    BlockingQueue<UpsertParams> upserts = new LinkedBlockingQueue<>();
    BlockingQueue<ChangeSet<ItemDescriptor>> applies = new LinkedBlockingQueue<>();
    boolean valid;
    
    @Override
    public boolean init(FullDataSet<ItemDescriptor> allData) {
      inits.add(allData);
      return true;
    }

    @Override
    public boolean upsert(DataKind kind, String key, ItemDescriptor item) {
      upserts.add(new UpsertParams(kind, key, item));
      return true;
    }

    @Override
    public DataStoreStatusProvider getDataStoreStatusProvider() {
      return null;
    }

    @Override
    public void updateStatus(State newState, ErrorInfo newError) {
      valid = newState == State.VALID;
    }
    
    @Override
    public boolean apply(ChangeSet<ItemDescriptor> changeSet) {
      applies.add(changeSet);
      return true;
    }
  }
}
