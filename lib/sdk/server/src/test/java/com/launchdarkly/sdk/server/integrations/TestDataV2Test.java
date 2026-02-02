package com.launchdarkly.sdk.server.integrations;

import com.google.common.collect.ImmutableMap;
import com.launchdarkly.sdk.ContextKind;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.DataModel;
import com.launchdarkly.sdk.server.LDConfig;
import com.launchdarkly.sdk.server.ModelBuilders;
import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;
import com.launchdarkly.sdk.server.datasources.SelectorSource;
import com.launchdarkly.sdk.server.datasources.Synchronizer;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider.ErrorInfo;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider.State;
import com.launchdarkly.sdk.server.interfaces.DataStoreStatusProvider;
import com.launchdarkly.sdk.server.subsystems.ClientContext;
import com.launchdarkly.sdk.server.subsystems.DataSourceBuildInputs;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ChangeSet;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ChangeSetType;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;

import com.launchdarkly.sdk.internal.fdv2.sources.Selector;
import org.junit.Test;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.google.common.collect.Iterables.get;
import static com.launchdarkly.sdk.server.ModelBuilders.flagBuilder;
import static com.launchdarkly.sdk.server.TestComponents.clientContext;
import static com.launchdarkly.sdk.server.TestComponents.nullLogger;
import static com.launchdarkly.sdk.server.TestComponents.sharedExecutor;
import static com.launchdarkly.testhelpers.JsonAssertions.assertJsonEquals;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.iterableWithSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@SuppressWarnings("javadoc")
public class TestDataV2Test {
  private static final LDValue[] THREE_STRING_VALUES =
      new LDValue[] { LDValue.of("red"), LDValue.of("green"), LDValue.of("blue") };

  private final CapturingDataSourceUpdates updates = new CapturingDataSourceUpdates();

  private DataSourceBuildInputs dataSourceBuildInputs() {
    ClientContext context = clientContext("", new LDConfig.Builder().build(), updates);
    SelectorSource selectorSource = () -> Selector.EMPTY;
    return new DataSourceBuildInputs(
        nullLogger,
        0,
        updates,
        context.getServiceEndpoints(),
        context.getHttp(),
        sharedExecutor,
        null,
        selectorSource);
  }

  @Test
  public void initializesWithEmptyData() throws Exception {
    TestDataV2 td = TestDataV2.synchronizer();
    Synchronizer sync = td.build(dataSourceBuildInputs());

    FDv2SourceResult result = sync.next().get(5, TimeUnit.SECONDS);

    assertThat(result.getResultType(), equalTo(FDv2SourceResult.ResultType.CHANGE_SET));
    ChangeSet<ItemDescriptor> changeSet = result.getChangeSet();
    assertThat(changeSet, notNullValue());
    assertThat(changeSet.getType(), equalTo(ChangeSetType.Full));
    assertThat(changeSet.getData(), iterableWithSize(1));
    assertThat(get(changeSet.getData(), 0).getKey(), equalTo(DataModel.FEATURES));
    assertThat(get(changeSet.getData(), 0).getValue().getItems(), emptyIterable());
  }

  @Test
  public void initializesWithFlags() throws Exception {
    TestDataV2 td = TestDataV2.synchronizer();
    td.update(td.flag("flag1").on(true))
      .update(td.flag("flag2").on(false));

    Synchronizer sync = td.build(dataSourceBuildInputs());
    FDv2SourceResult result = sync.next().get(5, TimeUnit.SECONDS);

    assertThat(result.getResultType(), equalTo(FDv2SourceResult.ResultType.CHANGE_SET));
    ChangeSet<ItemDescriptor> changeSet = result.getChangeSet();
    assertThat(changeSet.getType(), equalTo(ChangeSetType.Full));
    assertThat(changeSet.getData(), iterableWithSize(1));
    assertThat(get(changeSet.getData(), 0).getKey(), equalTo(DataModel.FEATURES));
    assertThat(get(changeSet.getData(), 0).getValue().getItems(), iterableWithSize(2));

    ModelBuilders.FlagBuilder expectedFlag1 = flagBuilder("flag1").version(1).salt("")
        .on(true).offVariation(1).fallthroughVariation(0).variations(true, false);
    ModelBuilders.FlagBuilder expectedFlag2 = flagBuilder("flag2").version(1).salt("")
        .on(false).offVariation(1).fallthroughVariation(0).variations(true, false);

    Map<String, ItemDescriptor> flags = ImmutableMap.copyOf(get(changeSet.getData(), 0).getValue().getItems());
    ItemDescriptor flag1 = flags.get("flag1");
    ItemDescriptor flag2 = flags.get("flag2");
    assertThat(flag1, not(nullValue()));
    assertThat(flag2, not(nullValue()));

    assertJsonEquals(flagJson(expectedFlag1, 1), flagJson(flag1));
    assertJsonEquals(flagJson(expectedFlag2, 1), flagJson(flag2));
  }

  @Test
  public void addsFlag() throws Exception {
    TestDataV2 td = TestDataV2.synchronizer();
    Synchronizer sync = td.build(dataSourceBuildInputs());

    FDv2SourceResult initResult = sync.next().get(5, TimeUnit.SECONDS);
    assertThat(initResult.getResultType(), equalTo(FDv2SourceResult.ResultType.CHANGE_SET));
    assertThat(initResult.getChangeSet().getType(), equalTo(ChangeSetType.Full));

    td.update(td.flag("flag1").on(true));

    FDv2SourceResult updateResult = sync.next().get(5, TimeUnit.SECONDS);
    assertThat(updateResult.getResultType(), equalTo(FDv2SourceResult.ResultType.CHANGE_SET));
    ChangeSet<ItemDescriptor> changeSet = updateResult.getChangeSet();
    assertThat(changeSet.getType(), equalTo(ChangeSetType.Partial));
    assertThat(changeSet.getData(), iterableWithSize(1));
    KeyedItems<ItemDescriptor> keyedItems = get(changeSet.getData(), 0).getValue();
    Map<String, ItemDescriptor> items = ImmutableMap.copyOf(keyedItems.getItems());
    assertThat(items.size(), equalTo(1));
    ItemDescriptor flag1 = items.get("flag1");
    assertThat(flag1, not(nullValue()));

    ModelBuilders.FlagBuilder expectedFlag = flagBuilder("flag1").version(1).salt("")
        .on(true).offVariation(1).fallthroughVariation(0).variations(true, false);
    assertJsonEquals(flagJson(expectedFlag, 2), flagJson(flag1));
  }

  @Test
  public void updatesFlag() throws Exception {
    TestDataV2 td = TestDataV2.synchronizer();
    td.update(td.flag("flag1")
      .on(false)
      .variationForUser("a", true)
      .ifMatch("name", LDValue.of("Lucy")).thenReturn(true));

    Synchronizer sync = td.build(dataSourceBuildInputs());
    FDv2SourceResult initResult = sync.next().get(5, TimeUnit.SECONDS);
    assertThat(initResult.getResultType(), equalTo(FDv2SourceResult.ResultType.CHANGE_SET));

    td.update(td.flag("flag1").on(true));

    FDv2SourceResult updateResult = sync.next().get(5, TimeUnit.SECONDS);
    ChangeSet<ItemDescriptor> changeSet = updateResult.getChangeSet();
    Map<String, ItemDescriptor> items = ImmutableMap.copyOf(get(changeSet.getData(), 0).getValue().getItems());
    ItemDescriptor flag1 = items.get("flag1");

    ModelBuilders.FlagBuilder expectedFlag = flagBuilder("flag1").version(2).salt("")
        .on(true).offVariation(1).fallthroughVariation(0).variations(true, false)
        .addTarget(0, "a").addContextTarget(ContextKind.DEFAULT, 0)
        .addRule("rule0", 0, "{\"contextKind\":\"user\",\"attribute\":\"name\",\"op\":\"in\",\"values\":[\"Lucy\"]}");
    assertJsonEquals(flagJson(expectedFlag, 2), flagJson(flag1));
  }

  @Test
  public void deletesFlag() throws Exception {
    TestDataV2 td = TestDataV2.synchronizer();
    Synchronizer sync = td.build(dataSourceBuildInputs());

    sync.next().get(5, TimeUnit.SECONDS);

    td.update(td.flag("foo").on(false).valueForAll(LDValue.of("bar")));
    FDv2SourceResult addResult = sync.next().get(5, TimeUnit.SECONDS);
    assertThat(addResult.getChangeSet().getType(), equalTo(ChangeSetType.Partial));
    Map<String, ItemDescriptor> addItems = ImmutableMap.copyOf(get(addResult.getChangeSet().getData(), 0).getValue().getItems());
    assertThat(addItems.get("foo").getVersion(), equalTo(1));
    assertThat(addItems.get("foo").getItem(), notNullValue());

    td.delete("foo");
    FDv2SourceResult deleteResult = sync.next().get(5, TimeUnit.SECONDS);
    assertThat(deleteResult.getChangeSet().getType(), equalTo(ChangeSetType.Partial));
    Map<String, ItemDescriptor> deleteItems = ImmutableMap.copyOf(get(deleteResult.getChangeSet().getData(), 0).getValue().getItems());
    assertThat(deleteItems.get("foo").getVersion(), equalTo(2));
    assertThat(deleteItems.get("foo").getItem(), nullValue());

    sync.close();
  }

  @Test
  public void flagConfigSimpleBoolean() throws Exception {
    Function<ModelBuilders.FlagBuilder, ModelBuilders.FlagBuilder> expectedBooleanFlag = fb ->
      fb.on(true).variations(true, false).offVariation(1).fallthroughVariation(0);

    verifyFlag(f -> f, expectedBooleanFlag);
    verifyFlag(f -> f.booleanFlag(), expectedBooleanFlag);
    verifyFlag(f -> f.on(true), expectedBooleanFlag);
    verifyFlag(f -> f.on(false), fb -> expectedBooleanFlag.apply(fb).on(false));
    verifyFlag(f -> f.variationForAll(false), fb -> expectedBooleanFlag.apply(fb).fallthroughVariation(1));
    verifyFlag(f -> f.variationForAll(true), expectedBooleanFlag);
  }

  @Test
  public void flagConfigStringVariations() throws Exception {
    verifyFlag(
        f -> f.variations(THREE_STRING_VALUES).offVariation(0).fallthroughVariation(2),
        fb -> fb.variations("red", "green", "blue").on(true).offVariation(0).fallthroughVariation(2)
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
  }

  @Test
  public void flagRules() throws Exception {
    Function<ModelBuilders.FlagBuilder, ModelBuilders.FlagBuilder> expectedBooleanFlag = fb ->
      fb.variations(true, false).on(true).offVariation(1).fallthroughVariation(0);

    verifyFlag(
        f -> f.ifMatch("name", LDValue.of("Lucy")).thenReturn(true),
        fb -> expectedBooleanFlag.apply(fb).addRule("rule0", 0,
            "{\"contextKind\":\"user\",\"attribute\":\"name\",\"op\":\"in\",\"values\":[\"Lucy\"]}")
    );
  }

  private void verifyFlag(
      Function<TestData.FlagBuilder, TestData.FlagBuilder> configureFlag,
      Function<ModelBuilders.FlagBuilder, ModelBuilders.FlagBuilder> configureExpectedFlag
      ) throws Exception {
    ModelBuilders.FlagBuilder expectedFlag = flagBuilder("flagkey").version(1).salt("");
    expectedFlag = configureExpectedFlag.apply(expectedFlag);

    TestDataV2 td = TestDataV2.synchronizer();
    Synchronizer sync = td.build(dataSourceBuildInputs());
    sync.next().get(5, TimeUnit.SECONDS);

    td.update(configureFlag.apply(td.flag("flagkey")));

    FDv2SourceResult result = sync.next().get(5, TimeUnit.SECONDS);
    assertThat(result.getResultType(), equalTo(FDv2SourceResult.ResultType.CHANGE_SET));
    ChangeSet<ItemDescriptor> changeSet = result.getChangeSet();
    Map<String, ItemDescriptor> items = ImmutableMap.copyOf(get(changeSet.getData(), 0).getValue().getItems());
    ItemDescriptor flag = items.get("flagkey");
    assertJsonEquals(flagJson(expectedFlag, 1), flagJson(flag));
  }

  private static String flagJson(ModelBuilders.FlagBuilder flagBuilder, int version) {
    return DataModel.FEATURES.serialize(new ItemDescriptor(version, flagBuilder.build()));
  }

  private static String flagJson(ItemDescriptor flag) {
    return DataModel.FEATURES.serialize(flag);
  }

  private static class CapturingDataSourceUpdates implements com.launchdarkly.sdk.server.subsystems.DataSourceUpdateSink,
      com.launchdarkly.sdk.server.subsystems.DataSourceUpdateSinkV2 {
    BlockingQueue<com.launchdarkly.sdk.server.subsystems.DataStoreTypes.FullDataSet<ItemDescriptor>> inits =
        new LinkedBlockingQueue<>();
    BlockingQueue<UpsertParams> upserts = new LinkedBlockingQueue<>();
    BlockingQueue<ChangeSet<ItemDescriptor>> applies = new LinkedBlockingQueue<>();
    boolean valid;

    @Override
    public boolean init(com.launchdarkly.sdk.server.subsystems.DataStoreTypes.FullDataSet<ItemDescriptor> allData) {
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
}
