package com.launchdarkly.sdk.server;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.launchdarkly.sdk.fdv2.Selector;
import com.launchdarkly.sdk.server.DataModel.FeatureFlag;
import com.launchdarkly.sdk.server.DataModel.Segment;
import com.launchdarkly.sdk.server.DataStoreTestTypes.DataBuilder;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider.ErrorInfo;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider.ErrorKind;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider.State;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider.Status;
import com.launchdarkly.sdk.server.interfaces.DataStoreStatusProvider;
import com.launchdarkly.sdk.server.subsystems.DataStore;
import com.launchdarkly.sdk.fdv2.ChangeSet;
import com.launchdarkly.sdk.fdv2.ChangeSetType;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.FullDataSet;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;
import com.launchdarkly.sdk.server.interfaces.FlagChangeEvent;
import com.launchdarkly.sdk.server.interfaces.FlagChangeListener;

import org.easymock.Capture;
import org.easymock.EasyMock;
import org.easymock.EasyMockSupport;
import org.junit.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.launchdarkly.sdk.server.DataModel.FEATURES;
import static com.launchdarkly.sdk.server.DataModel.SEGMENTS;
import static com.launchdarkly.sdk.server.ModelBuilders.flagBuilder;
import static com.launchdarkly.sdk.server.ModelBuilders.prerequisite;
import static com.launchdarkly.sdk.server.ModelBuilders.ruleBuilder;
import static com.launchdarkly.sdk.server.ModelBuilders.segmentBuilder;
import static com.launchdarkly.sdk.server.ModelBuilders.segmentRuleBuilder;
import static com.launchdarkly.sdk.server.TestComponents.inMemoryDataStore;
import static com.launchdarkly.sdk.server.TestComponents.nullLogger;
import static com.launchdarkly.sdk.server.TestComponents.sharedExecutor;
import static com.launchdarkly.sdk.server.TestUtil.expectEvents;
import static com.launchdarkly.testhelpers.ConcurrentHelpers.assertNoMoreValues;
import static com.launchdarkly.testhelpers.ConcurrentHelpers.awaitValue;
import static org.easymock.EasyMock.replay;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;

@SuppressWarnings("javadoc")
public class DataSourceUpdatesImplTest {
  // Note that these tests must use the actual data model types for flags and segments, rather than the
  // TestItem type from DataStoreTestTypes, because the dependency behavior is based on the real data model.
  
  private final EventBroadcasterImpl<FlagChangeListener, FlagChangeEvent> flagChangeBroadcaster =
      EventBroadcasterImpl.forFlagChangeEvents(TestComponents.sharedExecutor, nullLogger);
  private final EasyMockSupport mocks = new EasyMockSupport();
  
  private DataSourceUpdatesImpl makeInstance(DataStore store) {
    return makeInstance(store, EventBroadcasterImpl.forDataSourceStatus(sharedExecutor, nullLogger));
  }

  private DataSourceUpdatesImpl makeInstance(
      DataStore store,
      EventBroadcasterImpl<DataSourceStatusProvider.StatusListener, DataSourceStatusProvider.Status> statusBroadcaster
      ) {
    return new DataSourceUpdatesImpl(store, null, flagChangeBroadcaster, statusBroadcaster, sharedExecutor, null, nullLogger);
  }
  
  // ===== Private helper functions =====
  
  private static FeatureFlag flagWithPrerequisiteReference(FeatureFlag fromFlag, FeatureFlag toFlag) {
    List<DataModel.Prerequisite> prereqs = new ArrayList<>(fromFlag.getPrerequisites());
    prereqs.add(prerequisite(toFlag.getKey(), 0));
    return flagBuilder(fromFlag.getKey())
        .version(fromFlag.getVersion())
        .prerequisites(prereqs.toArray(new DataModel.Prerequisite[0]))
        .build();
  }
  
  private static FeatureFlag flagWithSegmentReference(FeatureFlag flag, Segment... segments) {
    String[] segmentKeys = new String[segments.length];
    for (int i = 0; i < segments.length; i++) {
      segmentKeys[i] = segments[i].getKey();
    }
    return flagBuilder(flag.getKey())
        .version(flag.getVersion())
        .rules(ruleBuilder().clauses(ModelBuilders.clauseMatchingSegment(segmentKeys)).build())
        .build();
  }
  
  private static Segment segmentWithSegmentReference(Segment segment, Segment... segments) {
    String[] segmentKeys = new String[segments.length];
    for (int i = 0; i < segments.length; i++) {
      segmentKeys[i] = segments[i].getKey();
    }
    return segmentBuilder(segment.getKey())
        .version(segment.getVersion())
        .rules(segmentRuleBuilder().clauses(ModelBuilders.clauseMatchingSegment(segmentKeys)).build())
        .build();
  }
  
  private static FeatureFlag nextVersion(FeatureFlag flag) {
    return flagBuilder(flag.getKey()).version(flag.getVersion() + 1).build();
  }
  
  private static Segment nextVersion(Segment segment) {
    return segmentBuilder(segment.getKey()).version(segment.getVersion() + 1).build();
  }
  
  private static ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> makeFullChangeSet(FeatureFlag... flags) {
    List<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> data = new ArrayList<>();
    if (flags.length > 0) {
      Map<String, ItemDescriptor> flagItems = new HashMap<>();
      for (FeatureFlag flag : flags) {
        flagItems.put(flag.getKey(), new ItemDescriptor(flag.getVersion(), flag));
      }
      data.add(new AbstractMap.SimpleEntry<>(
          FEATURES,
          new KeyedItems<>(ImmutableList.copyOf(flagItems.entrySet()))
      ));
    }
    return new ChangeSet<>(
        ChangeSetType.Full,
        Selector.make(1, "state1"),
        data,
        null,
        true
    );
  }
  
  private static ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> makePartialChangeSet(FeatureFlag... flags) {
    List<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> data = new ArrayList<>();
    if (flags.length > 0) {
      Map<String, ItemDescriptor> flagItems = new HashMap<>();
      for (FeatureFlag flag : flags) {
        flagItems.put(flag.getKey(), new ItemDescriptor(flag.getVersion(), flag));
      }
      data.add(new AbstractMap.SimpleEntry<>(
          FEATURES,
          new KeyedItems<>(ImmutableList.copyOf(flagItems.entrySet()))
      ));
    }
    return new ChangeSet<>(
        ChangeSetType.Partial,
        Selector.make(1, "state1"),
        data,
        null,
        true
    );
  }

  private static class LegacyDataStore implements DataStore {
    private final Map<DataKind, Map<String, ItemDescriptor>> data = new HashMap<>();
    
    @Override
    public void init(FullDataSet<ItemDescriptor> allData) {
      data.clear();
      for (Map.Entry<DataKind, KeyedItems<ItemDescriptor>> kindEntry : allData.getData()) {
        DataKind kind = kindEntry.getKey();
        Map<String, ItemDescriptor> items = new HashMap<>();
        for (Map.Entry<String, ItemDescriptor> itemEntry : kindEntry.getValue().getItems()) {
          items.put(itemEntry.getKey(), itemEntry.getValue());
        }
        data.put(kind, items);
      }
    }
    
    @Override
    public boolean upsert(DataKind kind, String key, ItemDescriptor item) {
      Map<String, ItemDescriptor> items = data.get(kind);
      if (items == null) {
        items = new HashMap<>();
        data.put(kind, items);
      }
      
      ItemDescriptor oldItem = items.get(key);
      if (oldItem != null && oldItem.getVersion() >= item.getVersion()) {
        return false;
      }
      
      items.put(key, item);
      return true;
    }
    
    @Override
    public ItemDescriptor get(DataKind kind, String key) {
      Map<String, ItemDescriptor> items = data.get(kind);
      if (items != null) {
        return items.get(key);
      }
      return null;
    }
    
    @Override
    public KeyedItems<ItemDescriptor> getAll(DataKind kind) {
      Map<String, ItemDescriptor> items = data.get(kind);
      if (items != null) {
        return new KeyedItems<>(ImmutableList.copyOf(items.entrySet()));
      }
      return new KeyedItems<>(ImmutableList.of());
    }
    
    @Override
    public boolean isInitialized() {
      return !data.isEmpty();
    }
    
    @Override
    public boolean isStatusMonitoringEnabled() {
      return false;
    }
    
    @Override
    public DataStoreStatusProvider.CacheStats getCacheStats() {
      return null;
    }
    
    @Override
    public void close() throws IOException {
      // No-op
    }
  }
  
  @Test
  public void sendsEventsOnInitForNewlyAddedFlags() throws Exception {
    DataStore store = inMemoryDataStore();
    DataBuilder builder = new DataBuilder()
        .addAny(FEATURES,
            flagBuilder("flag1").version(1).build())
        .addAny(SEGMENTS,
            segmentBuilder("segment1").version(1).build());
        
    DataSourceUpdatesImpl storeUpdates = makeInstance(store);

    storeUpdates.init(builder.build());

    BlockingQueue<FlagChangeEvent> eventSink = new LinkedBlockingQueue<>();
    flagChangeBroadcaster.register(eventSink::add);
    
    builder.addAny(FEATURES, flagBuilder("flag2").version(1).build())
        .addAny(SEGMENTS, segmentBuilder("segment2").version(1).build());
    // the new segment triggers no events since nothing is using it
    
    storeUpdates.init(builder.build());
  
    expectEvents(eventSink, "flag2");
  }

  @Test
  public void sendsEventOnUpdateForNewlyAddedFlag() throws Exception {
    DataStore store = inMemoryDataStore();
    DataBuilder builder = new DataBuilder()
        .addAny(FEATURES,
            flagBuilder("flag1").version(1).build())
        .addAny(SEGMENTS,
            segmentBuilder("segment1").version(1).build());
    
    DataSourceUpdatesImpl storeUpdates = makeInstance(store);

    storeUpdates.init(builder.build());
    
    BlockingQueue<FlagChangeEvent> eventSink = new LinkedBlockingQueue<>();
    flagChangeBroadcaster.register(eventSink::add);
    
    storeUpdates.upsert(FEATURES, "flag2", new ItemDescriptor(1, flagBuilder("flag2").version(1).build()));
  
    expectEvents(eventSink, "flag2");
  }
  
  @Test
  public void sendsEventsOnInitForUpdatedFlags() throws Exception {
    DataStore store = inMemoryDataStore();
    DataBuilder builder = new DataBuilder()
        .addAny(FEATURES,
            flagBuilder("flag1").version(1).build(),
            flagBuilder("flag2").version(1).build())
        .addAny(SEGMENTS,
            segmentBuilder("segment1").version(1).build(),
            segmentBuilder("segment2").version(1).build());
    
    DataSourceUpdatesImpl storeUpdates = makeInstance(store);

    storeUpdates.init(builder.build());

    BlockingQueue<FlagChangeEvent> eventSink = new LinkedBlockingQueue<>();
    flagChangeBroadcaster.register(eventSink::add);
    
    builder.addAny(FEATURES, flagBuilder("flag2").version(2).build()) // modified flag
        .addAny(SEGMENTS, segmentBuilder("segment2").version(2).build()); // modified segment, but it's irrelevant
    storeUpdates.init(builder.build());
    
    expectEvents(eventSink, "flag2");
  }

  @Test
  public void sendsEventOnUpdateForUpdatedFlag() throws Exception {
    DataStore store = inMemoryDataStore();
    DataBuilder builder = new DataBuilder()
        .addAny(FEATURES,
            flagBuilder("flag1").version(1).build(),
            flagBuilder("flag2").version(1).build())
        .addAny(SEGMENTS,
            segmentBuilder("segment1").version(1).build());
    
    DataSourceUpdatesImpl storeUpdates = makeInstance(store);

    storeUpdates.init(builder.build());

    BlockingQueue<FlagChangeEvent> eventSink = new LinkedBlockingQueue<>();
    flagChangeBroadcaster.register(eventSink::add);
    
    storeUpdates.upsert(FEATURES, "flag2", new ItemDescriptor(2, flagBuilder("flag2").version(2).build()));
  
    expectEvents(eventSink, "flag2");
  }

  @Test
  public void doesNotSendsEventOnUpdateIfItemWasNotReallyUpdated() throws Exception {
    DataStore store = inMemoryDataStore();
    DataModel.FeatureFlag flag1 = flagBuilder("flag1").version(1).build();
    DataModel.FeatureFlag flag2 = flagBuilder("flag2").version(1).build();
    DataBuilder builder = new DataBuilder()
        .addAny(FEATURES, flag1, flag2);
    
    DataSourceUpdatesImpl storeUpdates = makeInstance(store);

    storeUpdates.init(builder.build());

    BlockingQueue<FlagChangeEvent> eventSink = new LinkedBlockingQueue<>();
    flagChangeBroadcaster.register(eventSink::add);
    
    storeUpdates.upsert(FEATURES, flag2.getKey(), new ItemDescriptor(flag2.getVersion(), flag2));
  
    assertNoMoreValues(eventSink, 100, TimeUnit.MILLISECONDS);
  }
  
  @Test
  public void sendsEventsOnInitForDeletedFlags() throws Exception {
    DataStore store = inMemoryDataStore();
    DataBuilder builder = new DataBuilder()
        .addAny(FEATURES,
            flagBuilder("flag1").version(1).build(),
            flagBuilder("flag2").version(1).build())
        .addAny(SEGMENTS,
            segmentBuilder("segment1").version(1).build());
    
    DataSourceUpdatesImpl storeUpdates = makeInstance(store);

    storeUpdates.init(builder.build());

    BlockingQueue<FlagChangeEvent> eventSink = new LinkedBlockingQueue<>();
    flagChangeBroadcaster.register(eventSink::add);
    
    builder.remove(FEATURES, "flag2");
    builder.remove(SEGMENTS, "segment1"); // deleted segment isn't being used so it's irrelevant
    // note that the full data set for init() will never include deleted item placeholders
    
    storeUpdates.init(builder.build());
  
    expectEvents(eventSink, "flag2");
  }

  @Test
  public void sendsEventOnUpdateForDeletedFlag() throws Exception {
    DataStore store = inMemoryDataStore();
    DataBuilder builder = new DataBuilder()
        .addAny(FEATURES,
            flagBuilder("flag1").version(1).build(),
            flagBuilder("flag2").version(1).build())
        .addAny(SEGMENTS,
            segmentBuilder("segment1").version(1).build());
    
    DataSourceUpdatesImpl storeUpdates = makeInstance(store);

    storeUpdates.init(builder.build());
    
    BlockingQueue<FlagChangeEvent> events = new LinkedBlockingQueue<>();
    flagChangeBroadcaster.register(events::add);
    
    storeUpdates.upsert(FEATURES, "flag2", ItemDescriptor.deletedItem(2));
  
    expectEvents(events, "flag2");
  }

  @Test
  public void sendsEventsOnInitForFlagsWhosePrerequisitesChanged() throws Exception {
    DataStore store = inMemoryDataStore();
    DataBuilder builder = new DataBuilder()
        .addAny(FEATURES,
            flagBuilder("flag1").version(1).build(),
            flagBuilder("flag2").version(1).prerequisites(prerequisite("flag1", 0)).build(),
            flagBuilder("flag3").version(1).build(),
            flagBuilder("flag4").version(1).prerequisites(prerequisite("flag1", 0)).build(),
            flagBuilder("flag5").version(1).prerequisites(prerequisite("flag4", 0)).build(),
            flagBuilder("flag6").version(1).build());
    
    DataSourceUpdatesImpl storeUpdates = makeInstance(store);
    
    storeUpdates.init(builder.build());
    
    BlockingQueue<FlagChangeEvent> eventSink = new LinkedBlockingQueue<>();
    flagChangeBroadcaster.register(eventSink::add);
  
    builder.addAny(FEATURES, flagBuilder("flag1").version(2).build());
    storeUpdates.init(builder.build());
  
    expectEvents(eventSink, "flag1", "flag2", "flag4", "flag5");
  }

  @Test
  public void sendsEventsOnUpdateForFlagsWhosePrerequisitesChanged() throws Exception {
    DataStore store = inMemoryDataStore();
    DataBuilder builder = new DataBuilder()
        .addAny(FEATURES,
            flagBuilder("flag1").version(1).build(),
            flagBuilder("flag2").version(1).prerequisites(prerequisite("flag1", 0)).build(),
            flagBuilder("flag3").version(1).build(),
            flagBuilder("flag4").version(1).prerequisites(prerequisite("flag1", 0)).build(),
            flagBuilder("flag5").version(1).prerequisites(prerequisite("flag4", 0)).build(),
            flagBuilder("flag6").version(1).build());
    
    DataSourceUpdatesImpl storeUpdates = makeInstance(store);

    storeUpdates.init(builder.build());
    
    BlockingQueue<FlagChangeEvent> eventSink = new LinkedBlockingQueue<>();
    flagChangeBroadcaster.register(eventSink::add);
    
    storeUpdates.upsert(FEATURES, "flag1", new ItemDescriptor(2, flagBuilder("flag1").version(2).build()));
  
    expectEvents(eventSink, "flag1", "flag2", "flag4", "flag5");
  }

  @Test
  public void sendsEventsOnInitForFlagsWhoseSegmentsChanged() throws Exception {
    DataStore store = inMemoryDataStore();
    DataBuilder builder = new DataBuilder()
        .addAny(FEATURES,
            flagBuilder("flag1").version(1).build(),
            flagBuilder("flag2").version(1).rules(
                ruleBuilder().clauses(
                    ModelBuilders.clauseMatchingSegment("segment1")
                    ).build()
                ).build(),
            flagBuilder("flag3").version(1).build(),
            flagBuilder("flag4").version(1).prerequisites(prerequisite("flag2", 0)).build())
        .addAny(SEGMENTS,
            segmentBuilder("segment1").version(1).build(),
            segmentBuilder("segment2").version(1).build());
    
    DataSourceUpdatesImpl storeUpdates = makeInstance(store);

    storeUpdates.init(builder.build());
    
    BlockingQueue<FlagChangeEvent> eventSink = new LinkedBlockingQueue<>();
    flagChangeBroadcaster.register(eventSink::add);
    
    storeUpdates.upsert(SEGMENTS, "segment1", new ItemDescriptor(2, segmentBuilder("segment1").version(2).build()));
  
    expectEvents(eventSink, "flag2", "flag4");
  }
  
  @Test
  public void sendsEventsOnUpdateForFlagsWhoseSegmentsChanged() throws Exception {
    DataStore store = inMemoryDataStore();
    DataBuilder builder = new DataBuilder()
        .addAny(FEATURES,
            flagBuilder("flag1").version(1).build(),
            flagBuilder("flag2").version(1).rules(
                ruleBuilder().clauses(
                    ModelBuilders.clauseMatchingSegment("segment1")
                    ).build()
                ).build(),
            flagBuilder("flag3").version(1).build(),
            flagBuilder("flag4").version(1).prerequisites(prerequisite("flag2", 0)).build())
        .addAny(SEGMENTS,
            segmentBuilder("segment1").version(1).build(),
            segmentBuilder("segment2").version(1).build());

    DataSourceUpdatesImpl storeUpdates = makeInstance(store);

    storeUpdates.init(builder.build());
    
    BlockingQueue<FlagChangeEvent> eventSink = new LinkedBlockingQueue<>();
    flagChangeBroadcaster.register(eventSink::add);
    
    builder.addAny(SEGMENTS, segmentBuilder("segment1").version(2).build());
    storeUpdates.init(builder.build());
    
    expectEvents(eventSink, "flag2", "flag4");
  }

  @Test
  public void dataSetIsPassedToDataStoreInCorrectOrder() throws Exception {
    // The logic for this is already tested in DataModelDependenciesTest, but here we are verifying
    // that DataSourceUpdatesImpl is actually using DataModelDependencies.
    Capture<FullDataSet<ItemDescriptor>> captureData = Capture.newInstance();
    DataStore store = mocks.createStrictMock(DataStore.class);
    store.init(EasyMock.capture(captureData));
    replay(store);
    
    DataSourceUpdatesImpl storeUpdates = makeInstance(store);
    storeUpdates.init(DataModelDependenciesTest.DEPENDENCY_ORDERING_TEST_DATA);
    
    DataModelDependenciesTest.verifySortedData(captureData.getValue(),
        DataModelDependenciesTest.DEPENDENCY_ORDERING_TEST_DATA);

  }

  @Test
  public void updateStatusBroadcastsNewStatus() {
    EventBroadcasterImpl<DataSourceStatusProvider.StatusListener, DataSourceStatusProvider.Status> broadcaster =
        EventBroadcasterImpl.forDataSourceStatus(sharedExecutor, nullLogger);
    DataSourceUpdatesImpl updates = makeInstance(inMemoryDataStore(), broadcaster);
    
    BlockingQueue<Status> statuses = new LinkedBlockingQueue<>();
    broadcaster.register(statuses::add);
    
    Instant timeBeforeUpdate = Instant.now();
    ErrorInfo errorInfo = ErrorInfo.fromHttpError(401);
    updates.updateStatus(State.OFF, errorInfo);
    
    Status status = awaitValue(statuses, 500, TimeUnit.MILLISECONDS);
    
    assertThat(status.getState(), is(State.OFF));
    assertThat(status.getStateSince(), greaterThanOrEqualTo(timeBeforeUpdate));
    assertThat(status.getLastError(), is(errorInfo));
  }

  @Test
  public void updateStatusKeepsStateUnchangedIfStateWasInitializingAndNewStateIsInterrupted() {
    EventBroadcasterImpl<DataSourceStatusProvider.StatusListener, DataSourceStatusProvider.Status> broadcaster =
        EventBroadcasterImpl.forDataSourceStatus(sharedExecutor, nullLogger);
    DataSourceUpdatesImpl updates = makeInstance(inMemoryDataStore(), broadcaster);
    
    assertThat(updates.getLastStatus().getState(), is(State.INITIALIZING));
    Instant originalTime = updates.getLastStatus().getStateSince();
    
    BlockingQueue<Status> statuses = new LinkedBlockingQueue<>();
    broadcaster.register(statuses::add);
    
    ErrorInfo errorInfo = ErrorInfo.fromHttpError(401);
    updates.updateStatus(State.INTERRUPTED, errorInfo);
    
    Status status = awaitValue(statuses, 500, TimeUnit.MILLISECONDS);
    
    assertThat(status.getState(), is(State.INITIALIZING));
    assertThat(status.getStateSince(), is(originalTime));
    assertThat(status.getLastError(), is(errorInfo));
  }

  @Test
  public void updateStatusDoesNothingIfParametersHaveNoNewData() {
    EventBroadcasterImpl<DataSourceStatusProvider.StatusListener, DataSourceStatusProvider.Status> broadcaster =
        EventBroadcasterImpl.forDataSourceStatus(sharedExecutor, nullLogger);
    DataSourceUpdatesImpl updates = makeInstance(inMemoryDataStore(), broadcaster);
    
    BlockingQueue<Status> statuses = new LinkedBlockingQueue<>();
    broadcaster.register(statuses::add);
    
    updates.updateStatus(null, null);
    updates.updateStatus(State.INITIALIZING, null);

    assertNoMoreValues(statuses, 100, TimeUnit.MILLISECONDS);
  }
  
  @Test
  public void outageTimeoutLogging() throws Exception {
    BlockingQueue<String> outageErrors = new LinkedBlockingQueue<>();
    Duration outageTimeout = Duration.ofMillis(100);
    
    DataSourceUpdatesImpl updates = new DataSourceUpdatesImpl(
        inMemoryDataStore(),
        null,
        flagChangeBroadcaster,
        EventBroadcasterImpl.forDataSourceStatus(sharedExecutor, nullLogger),
        sharedExecutor,
        outageTimeout,
        nullLogger
    );
    updates.onOutageErrorLog = outageErrors::add;
    
    // simulate an outage
    updates.updateStatus(State.INTERRUPTED, ErrorInfo.fromHttpError(500));
    
    // but recover from it immediately
    updates.updateStatus(State.VALID, null);
    
    // wait till the timeout would have elapsed - no special message should be logged
    assertNoMoreValues(outageErrors, outageTimeout.plus(Duration.ofMillis(20)).toMillis(), TimeUnit.MILLISECONDS);
    
    // simulate another outage
    updates.updateStatus(State.INTERRUPTED, ErrorInfo.fromHttpError(501));
    updates.updateStatus(State.INTERRUPTED, ErrorInfo.fromHttpError(502));
    updates.updateStatus(State.INTERRUPTED, ErrorInfo.fromException(ErrorKind.NETWORK_ERROR, new IOException("x")));
    updates.updateStatus(State.INTERRUPTED, ErrorInfo.fromHttpError(501));
    
    String errorsDesc = awaitValue(outageErrors, 250, TimeUnit.MILLISECONDS); // timing is approximate
    assertThat(errorsDesc, containsString("NETWORK_ERROR (1 time)"));
    assertThat(errorsDesc, containsString("ERROR_RESPONSE(501) (2 times)"));
    assertThat(errorsDesc, containsString("ERROR_RESPONSE(502) (1 time)"));
  }
  
  @Test
  public void applyFullChangeSetSendsEventsForNewlyAddedFlags() throws Exception {
    DataSourceUpdatesImpl updates = makeInstance(inMemoryDataStore());
    updates.apply(makeFullChangeSet(flagBuilder("flag1").version(1).build()));
    
    BlockingQueue<FlagChangeEvent> eventSink = new LinkedBlockingQueue<>();
    flagChangeBroadcaster.register(eventSink::add);
    
    updates.apply(makeFullChangeSet(
        flagBuilder("flag1").version(1).build(),
        flagBuilder("flag2").version(1).build()
    ));
    
    expectEvents(eventSink, "flag2");
  }
  
  @Test
  public void applyPartialChangeSetSendsEventForNewlyAddedFlag() throws Exception {
    DataSourceUpdatesImpl updates = makeInstance(inMemoryDataStore());
    updates.apply(makeFullChangeSet(flagBuilder("flag1").version(1).build()));
    
    BlockingQueue<FlagChangeEvent> eventSink = new LinkedBlockingQueue<>();
    flagChangeBroadcaster.register(eventSink::add);
    
    updates.apply(makePartialChangeSet(flagBuilder("flag2").version(1).build()));
    
    expectEvents(eventSink, "flag2");
  }
  
  @Test
  public void applyFullChangeSetSendsEventsForUpdatedFlag() throws Exception {
    FeatureFlag flag1 = flagBuilder("flag1").version(1).build();
    FeatureFlag flag2 = flagBuilder("flag2").version(1).build();
    DataSourceUpdatesImpl updates = makeInstance(inMemoryDataStore());
    updates.apply(makeFullChangeSet(flag1, flag2));
    
    BlockingQueue<FlagChangeEvent> eventSink = new LinkedBlockingQueue<>();
    flagChangeBroadcaster.register(eventSink::add);
    
    updates.apply(makeFullChangeSet(flag1, nextVersion(flag2)));
    
    expectEvents(eventSink, "flag2");
  }
  
  @Test
  public void applyPartialChangeSetSendsEventForUpdatedFlag() throws Exception {
    FeatureFlag flag1 = flagBuilder("flag1").version(1).build();
    FeatureFlag flag2 = flagBuilder("flag2").version(1).build();
    DataSourceUpdatesImpl updates = makeInstance(inMemoryDataStore());
    updates.apply(makeFullChangeSet(flag1, flag2));
    
    BlockingQueue<FlagChangeEvent> eventSink = new LinkedBlockingQueue<>();
    flagChangeBroadcaster.register(eventSink::add);
    
    updates.apply(makePartialChangeSet(nextVersion(flag2)));
    
    expectEvents(eventSink, "flag2");
  }
  
  @Test
  public void applyFullChangeSetSendsEventsForDeletedFlags() throws Exception {
    FeatureFlag flag1 = flagBuilder("flag1").version(1).build();
    FeatureFlag flag2 = flagBuilder("flag2").version(1).build();
    DataSourceUpdatesImpl updates = makeInstance(inMemoryDataStore());
    updates.apply(makeFullChangeSet(flag1, flag2));
    
    BlockingQueue<FlagChangeEvent> eventSink = new LinkedBlockingQueue<>();
    flagChangeBroadcaster.register(eventSink::add);
    
    updates.apply(makeFullChangeSet(flag1));
    
    expectEvents(eventSink, "flag2");
  }
  
  @Test
  public void applyPartialChangeSetSendsEventForDeletedFlag() throws Exception {
    FeatureFlag flag1 = flagBuilder("flag1").version(1).build();
    FeatureFlag flag2 = flagBuilder("flag2").version(1).build();
    DataSourceUpdatesImpl updates = makeInstance(inMemoryDataStore());
    updates.apply(makeFullChangeSet(flag1, flag2));
    
    BlockingQueue<FlagChangeEvent> eventSink = new LinkedBlockingQueue<>();
    flagChangeBroadcaster.register(eventSink::add);
    
    ItemDescriptor deletedFlag = ItemDescriptor.deletedItem(flag2.getVersion() + 1);
    Map<String, ItemDescriptor> flagItems = ImmutableMap.of(flag2.getKey(), deletedFlag);
    List<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> data = ImmutableList.of(
        new AbstractMap.SimpleEntry<>(
            FEATURES,
            new KeyedItems<>(ImmutableList.copyOf(flagItems.entrySet()))
        )
    );
    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> changeSet = new ChangeSet<>(
        ChangeSetType.Partial,
        Selector.make(1, "state1"),
        data,
        null,
        true
    );
    updates.apply(changeSet);
    
    expectEvents(eventSink, "flag2");
  }
  
  @Test
  public void applyFullChangeSetSendsEventsForFlagsWhosePrerequisitesChanged() throws Exception {
    FeatureFlag flag1 = flagBuilder("flag1").version(1).build();
    FeatureFlag flag2 = flagBuilder("flag2").version(1).build();
    FeatureFlag flag3 = flagBuilder("flag3").version(1).build();
    FeatureFlag flag4 = flagBuilder("flag4").version(1).build();
    FeatureFlag flag5 = flagBuilder("flag5").version(1).build();
    FeatureFlag flag6 = flagBuilder("flag6").version(1).build();
    
    DataSourceUpdatesImpl updates = makeInstance(inMemoryDataStore());
    
    FeatureFlag[] initialFlags = {
        flag1,
        flagWithPrerequisiteReference(flag2, flag1),
        flag3,
        flagWithPrerequisiteReference(flag4, flag1),
        flagWithPrerequisiteReference(flag5, flag4),
        flag6
    };
    updates.apply(makeFullChangeSet(initialFlags));
    
    BlockingQueue<FlagChangeEvent> eventSink = new LinkedBlockingQueue<>();
    flagChangeBroadcaster.register(eventSink::add);
    
    FeatureFlag[] updatedFlags = {
        nextVersion(flag1),
        flagWithPrerequisiteReference(flag2, flag1),
        flag3,
        flagWithPrerequisiteReference(flag4, flag1),
        flagWithPrerequisiteReference(flag5, flag4),
        flag6
    };
    updates.apply(makeFullChangeSet(updatedFlags));
    
    expectEvents(eventSink, "flag1", "flag2", "flag4", "flag5");
  }
  
  @Test
  public void applyPartialChangeSetSendsEventsForFlagsWhosePrerequisitesChanged() throws Exception {
    FeatureFlag flag1 = flagBuilder("flag1").version(1).build();
    FeatureFlag flag2 = flagBuilder("flag2").version(1).build();
    FeatureFlag flag3 = flagBuilder("flag3").version(1).build();
    FeatureFlag flag4 = flagBuilder("flag4").version(1).build();
    FeatureFlag flag5 = flagBuilder("flag5").version(1).build();
    FeatureFlag flag6 = flagBuilder("flag6").version(1).build();
    
    DataSourceUpdatesImpl updates = makeInstance(inMemoryDataStore());
    
    FeatureFlag[] initialFlags = {
        flag1,
        flagWithPrerequisiteReference(flag2, flag1),
        flag3,
        flagWithPrerequisiteReference(flag4, flag1),
        flagWithPrerequisiteReference(flag5, flag4),
        flag6
    };
    updates.apply(makeFullChangeSet(initialFlags));
    
    BlockingQueue<FlagChangeEvent> eventSink = new LinkedBlockingQueue<>();
    flagChangeBroadcaster.register(eventSink::add);
    
    updates.apply(makePartialChangeSet(nextVersion(flag1)));
    
    expectEvents(eventSink, "flag1", "flag2", "flag4", "flag5");
  }
  
  @Test
  public void applyFullChangeSetSendsEventsForFlagsWhoseSegmentsChanged() throws Exception {
    Segment segment1 = segmentBuilder("segment1").version(1).build();
    Segment segment2 = segmentBuilder("segment2").version(1).build();
    Segment segment3 = segmentBuilder("segment3").version(1).build();
    Segment segment1WithSegment2Ref = segmentWithSegmentReference(segment1, segment2);
    
    FeatureFlag flag1 = flagBuilder("flag1").version(1).build();
    FeatureFlag flag2 = flagBuilder("flag2").version(1).build();
    FeatureFlag flag3 = flagBuilder("flag3").version(1).build();
    FeatureFlag flag4 = flagBuilder("flag4").version(1).build();
    
    DataSourceUpdatesImpl updates = makeInstance(inMemoryDataStore());
    
    FullDataSet<ItemDescriptor> initialData = new DataBuilder()
        .addAny(FEATURES,
            flag1,
            flagWithSegmentReference(flag2, segment1),
            flagWithSegmentReference(flag3, segment2),
            flagWithPrerequisiteReference(flag4, flag2))
        .addAny(SEGMENTS,
            segment1WithSegment2Ref,
            segment2,
            segment3)
        .build();
    updates.init(initialData);
    
    BlockingQueue<FlagChangeEvent> eventSink = new LinkedBlockingQueue<>();
    flagChangeBroadcaster.register(eventSink::add);
    
    Segment updatedSegment = nextVersion(segment1WithSegment2Ref);
    FullDataSet<ItemDescriptor> updatedData = new DataBuilder()
        .addAny(FEATURES,
            flag1,
            flagWithSegmentReference(flag2, segment1),
            flagWithSegmentReference(flag3, segment2),
            flagWithPrerequisiteReference(flag4, flag2))
        .addAny(SEGMENTS,
            updatedSegment,
            segment2,
            segment3)
        .build();
    
    List<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> changeSetData = new ArrayList<>();
    for (Map.Entry<DataKind, KeyedItems<ItemDescriptor>> kindEntry : updatedData.getData()) {
      changeSetData.add(new AbstractMap.SimpleEntry<>(
          kindEntry.getKey(),
          kindEntry.getValue()
      ));
    }
    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> changeSet = new ChangeSet<>(
        ChangeSetType.Full,
        Selector.make(1, "state1"),
        changeSetData,
        null,
        true
    );
    updates.apply(changeSet);
    
    expectEvents(eventSink, "flag2", "flag4");
  }
  
  @Test
  public void applyPartialChangeSetSendsEventsForFlagsWhoseSegmentsChanged() throws Exception {
    Segment segment1 = segmentBuilder("segment1").version(1).build();
    Segment segment2 = segmentBuilder("segment2").version(1).build();
    Segment segment3 = segmentBuilder("segment3").version(1).build();
    Segment segment1WithSegment2Ref = segmentWithSegmentReference(segment1, segment2);
    
    FeatureFlag flag1 = flagBuilder("flag1").version(1).build();
    FeatureFlag flag2 = flagBuilder("flag2").version(1).build();
    FeatureFlag flag3 = flagBuilder("flag3").version(1).build();
    FeatureFlag flag4 = flagBuilder("flag4").version(1).build();
    
    DataSourceUpdatesImpl updates = makeInstance(inMemoryDataStore());
    
    FullDataSet<ItemDescriptor> initialData = new DataBuilder()
        .addAny(FEATURES,
            flag1,
            flagWithSegmentReference(flag2, segment1),
            flagWithSegmentReference(flag3, segment2),
            flagWithPrerequisiteReference(flag4, flag2))
        .addAny(SEGMENTS,
            segment1WithSegment2Ref,
            segment2,
            segment3)
        .build();
    updates.init(initialData);
    
    BlockingQueue<FlagChangeEvent> eventSink = new LinkedBlockingQueue<>();
    flagChangeBroadcaster.register(eventSink::add);
    
    Segment updatedSegment = nextVersion(segment1WithSegment2Ref);
    Map<String, ItemDescriptor> segmentItems = ImmutableMap.of(
        updatedSegment.getKey(),
        new ItemDescriptor(updatedSegment.getVersion(), updatedSegment)
    );
    List<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> segmentData = ImmutableList.of(
        new AbstractMap.SimpleEntry<>(
            SEGMENTS,
            new KeyedItems<>(ImmutableList.copyOf(segmentItems.entrySet()))
        )
    );
    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> changeSet = new ChangeSet<>(
        ChangeSetType.Partial,
        Selector.make(1, "state1"),
        segmentData,
        null,
        true
    );
    updates.apply(changeSet);
    
    expectEvents(eventSink, "flag2", "flag4");
  }
  
  @Test
  public void applyFullChangeSetToLegacyStoreCallsInit() throws Exception {
    LegacyDataStore legacyStore = new LegacyDataStore();
    DataSourceUpdatesImpl updates = new DataSourceUpdatesImpl(
        legacyStore,
        null,
        flagChangeBroadcaster,
        EventBroadcasterImpl.forDataSourceStatus(sharedExecutor, nullLogger),
        sharedExecutor,
        null,
        nullLogger
    );
    
    FeatureFlag flag1 = flagBuilder("flag1").version(1).build();
    FeatureFlag flag2 = flagBuilder("flag2").version(1).build();
    updates.apply(makeFullChangeSet(flag1, flag2));
    
    ItemDescriptor retrievedFlag1 = legacyStore.get(FEATURES, flag1.getKey());
    ItemDescriptor retrievedFlag2 = legacyStore.get(FEATURES, flag2.getKey());
    
    assertThat(retrievedFlag1, is(org.hamcrest.Matchers.notNullValue()));
    assertThat(retrievedFlag2, is(org.hamcrest.Matchers.notNullValue()));
    assertThat(retrievedFlag1.getVersion(), is(flag1.getVersion()));
    assertThat(retrievedFlag2.getVersion(), is(flag2.getVersion()));
  }
  
  @Test
  public void applyPartialChangeSetToLegacyStoreCallsUpsert() throws Exception {
    LegacyDataStore legacyStore = new LegacyDataStore();
    DataSourceUpdatesImpl updates = new DataSourceUpdatesImpl(
        legacyStore,
        null,
        flagChangeBroadcaster,
        EventBroadcasterImpl.forDataSourceStatus(sharedExecutor, nullLogger),
        sharedExecutor,
        null,
        nullLogger
    );
    
    FeatureFlag flag1 = flagBuilder("flag1").version(1).build();
    FeatureFlag flag2 = flagBuilder("flag2").version(1).build();
    updates.apply(makeFullChangeSet(flag1));
    updates.apply(makePartialChangeSet(flag2));
    
    ItemDescriptor retrievedFlag1 = legacyStore.get(FEATURES, flag1.getKey());
    ItemDescriptor retrievedFlag2 = legacyStore.get(FEATURES, flag2.getKey());
    
    assertThat(retrievedFlag1, is(org.hamcrest.Matchers.notNullValue()));
    assertThat(retrievedFlag2, is(org.hamcrest.Matchers.notNullValue()));
    assertThat(retrievedFlag1.getVersion(), is(flag1.getVersion()));
    assertThat(retrievedFlag2.getVersion(), is(flag2.getVersion()));
  }
  
  @Test
  public void applyFullChangeSetToLegacyStoreSendsEvents() throws Exception {
    LegacyDataStore legacyStore = new LegacyDataStore();
    DataSourceUpdatesImpl updates = new DataSourceUpdatesImpl(
        legacyStore,
        null,
        flagChangeBroadcaster,
        EventBroadcasterImpl.forDataSourceStatus(sharedExecutor, nullLogger),
        sharedExecutor,
        null,
        nullLogger
    );
    
    FeatureFlag flag1 = flagBuilder("flag1").version(1).build();
    updates.apply(makeFullChangeSet(flag1));
    
    BlockingQueue<FlagChangeEvent> eventSink = new LinkedBlockingQueue<>();
    flagChangeBroadcaster.register(eventSink::add);
    
    FeatureFlag flag2 = flagBuilder("flag2").version(1).build();
    updates.apply(makeFullChangeSet(flag1, flag2));
    
    expectEvents(eventSink, "flag2");
  }
  
  @Test
  public void applyPartialChangeSetToLegacyStoreSendsEvents() throws Exception {
    LegacyDataStore legacyStore = new LegacyDataStore();
    DataSourceUpdatesImpl updates = new DataSourceUpdatesImpl(
        legacyStore,
        null,
        flagChangeBroadcaster,
        EventBroadcasterImpl.forDataSourceStatus(sharedExecutor, nullLogger),
        sharedExecutor,
        null,
        nullLogger
    );
    
    FeatureFlag flag1 = flagBuilder("flag1").version(1).build();
    updates.apply(makeFullChangeSet(flag1));
    
    BlockingQueue<FlagChangeEvent> eventSink = new LinkedBlockingQueue<>();
    flagChangeBroadcaster.register(eventSink::add);
    
    FeatureFlag flag2 = flagBuilder("flag2").version(1).build();
    updates.apply(makePartialChangeSet(flag2));
    
    expectEvents(eventSink, "flag2");
  }
  
  @Test
  public void applyFullChangeSetToLegacyStoreWithEnvironmentId() throws Exception {
    LegacyDataStore legacyStore = new LegacyDataStore();
    DataSourceUpdatesImpl updates = new DataSourceUpdatesImpl(
        legacyStore,
        null,
        flagChangeBroadcaster,
        EventBroadcasterImpl.forDataSourceStatus(sharedExecutor, nullLogger),
        sharedExecutor,
        null,
        nullLogger
    );
    
    FeatureFlag flag1 = flagBuilder("flag1").version(1).build();
    Map<String, ItemDescriptor> flagItems = ImmutableMap.of(
        flag1.getKey(),
        new ItemDescriptor(flag1.getVersion(), flag1)
    );
    List<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> data = ImmutableList.of(
        new AbstractMap.SimpleEntry<>(
            FEATURES,
            new KeyedItems<>(ImmutableList.copyOf(flagItems.entrySet()))
        )
    );
    ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> changeSet = new ChangeSet<>(
        ChangeSetType.Full,
        Selector.make(1, "state1"),
        data,
        "test-env-id",
        true
    );
    updates.apply(changeSet);
    
    // Note: Java SDK doesn't have InitMetadata/EnvironmentId support in the same way as C#,
    // so this test just verifies the changeset is applied without error
    ItemDescriptor retrievedFlag1 = legacyStore.get(FEATURES, flag1.getKey());
    assertThat(retrievedFlag1, is(org.hamcrest.Matchers.notNullValue()));
  }
}
