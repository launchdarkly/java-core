package com.launchdarkly.sdk.server.integrations;

import com.google.common.collect.ImmutableMap;
import com.launchdarkly.sdk.internal.collections.IterableAsyncQueue;
import com.launchdarkly.sdk.fdv2.Selector;
import com.launchdarkly.sdk.server.DataModel;
import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;
import com.launchdarkly.sdk.server.datasources.Synchronizer;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;
import com.launchdarkly.sdk.server.subsystems.DataSourceBuildInputs;
import com.launchdarkly.sdk.server.subsystems.DataSourceBuilder;
import com.launchdarkly.sdk.fdv2.ChangeSet;
import com.launchdarkly.sdk.fdv2.ChangeSetType;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A mechanism for providing dynamically updatable feature flag state as a {@link Synchronizer}
 * for use with the FDv2 data system in test scenarios.
 * <p>
 * Unlike {@link FileData}, this mechanism does not use any external resources. It provides only
 * the data that the application has put into it using the {@link #update(TestData.FlagBuilder)} method.
 * Use {@link TestData} when you need a legacy {@link com.launchdarkly.sdk.server.subsystems.DataSource};
 * use {@code TestDataV2} when configuring the client with {@link DataSystemBuilder#synchronizers(DataSourceBuilder[])}.
 * <p>
 * <pre><code>
 *     TestDataV2 td = TestDataV2.synchronizer();
 *     td.update(td.flag("flag-key-1").booleanFlag().variationForAllUsers(true));
 *
 *     LDConfig config = new LDConfig.Builder()
 *         .dataSystem(new DataSystemBuilder().synchronizers(td))
 *         .build();
 *     LDClient client = new LDClient(sdkKey, config);
 *
 *     td.update(td.flag("flag-key-2")
 *         .variationForUser("some-user-key", true)
 *         .fallthroughVariation(false));
 * </code></pre>
 * <p>
 * The above example uses a simple boolean flag. More complex configurations are possible using
 * the methods of the {@link TestData.FlagBuilder} returned by {@link #flag(String)}. {@link TestData.FlagBuilder}
 * supports many of the ways a flag can be configured on the LaunchDarkly dashboard, but does not
 * currently support 1. rule operators other than "in" and "not in", or 2. percentage rollouts.
 * <p>
 * If the same {@code TestDataV2} instance is used to configure multiple clients, any changes
 * made via {@link #update(TestData.FlagBuilder)}, {@link #delete(String)}, and
 * {@link #updateStatus(DataSourceStatusProvider.State, DataSourceStatusProvider.ErrorInfo)}
 * propagate to all configured synchronizers.
 *
 * @since 7.11.0
 */
public final class TestDataV2 implements DataSourceBuilder<Synchronizer> {
  private final Object lock = new Object();
  private final Map<String, ItemDescriptor> currentFlags = new HashMap<>();
  private final Map<String, TestData.FlagBuilder> currentBuilders = new HashMap<>();
  private final List<TestDataV2SynchronizerImpl> synchronizerInstances = new CopyOnWriteArrayList<>();
  private volatile boolean shouldPersist = true; // defaulting to true since this is more likely to be used for testing
  
  /**
   * Creates a new instance of the test synchronizer.
   * <p>
   * See {@link TestDataV2} for details.
   * 
   * @return a new configurable test synchronizer
   */
  public static TestDataV2 synchronizer() {
    return new TestDataV2();
  }

  private TestDataV2() {}
  
  /**
   * Creates or copies a {@link TestData.FlagBuilder} for building a test flag configuration.
   * <p>
   * If this flag key has already been defined in this {@code TestDataV2} instance, then the builder
   * starts with the same configuration that was last provided for this flag.
   * <p>
   * Otherwise, it starts with a new default configuration in which the flag has {@code true} and
   * {@code false} variations, is {@code true} for all users when targeting is turned on and
   * {@code false} otherwise, and currently has targeting turned on. You can change any of those
   * properties, and provide more complex behavior, using the {@link TestData.FlagBuilder} methods.
   * <p>
   * Once you have set the desired configuration, pass the builder to {@link #update(TestData.FlagBuilder)}.
   * 
   * @param key the flag key
   * @return a flag configuration builder
   * @see #update(TestData.FlagBuilder)
   */
  public TestData.FlagBuilder flag(String key) {
    TestData.FlagBuilder existingBuilder;
    synchronized (lock) {
      existingBuilder = currentBuilders.get(key);
    }
    if (existingBuilder != null) {
      return new TestData.FlagBuilder(existingBuilder);
    }
    return new TestData.FlagBuilder(key).booleanFlag();
  }

  /**
   * Deletes a specific flag from the test data by create a versioned tombstone.
   * <p>
   * This has the same effect as if a flag were removed on the LaunchDarkly dashboard.
   * It immediately propagates the flag change to any {@code LDClient} instance(s) that you have
   * already configured to use this {@code TestDataV2}. If no {@code LDClient} has been started yet,
   * it simply adds tombstone to the test data which will be provided to any {@code LDClient} that
   * you subsequently configure.
   *
   * @param key the flag key
   * @return a flag configuration builder
   */
  public TestDataV2 delete(String key) {
    final ItemDescriptor tombstoneItem;
    synchronized (lock) {
      final ItemDescriptor oldItem = currentFlags.get(key);
      final int oldVersion = oldItem == null ? 0 : oldItem.getVersion();
      tombstoneItem = ItemDescriptor.deletedItem(oldVersion + 1);
      currentFlags.put(key, tombstoneItem);
      currentBuilders.remove(key);
    }

    pushToSynchronizers(FDv2SourceResult.changeSet(makePartialChangeSet(key, tombstoneItem), false));

    return this;
  }

  /**
   * Updates the test data with the specified flag configuration.
   * <p>
   * This has the same effect as if a flag were added or modified on the LaunchDarkly dashboard.
   * It immediately propagates the flag change to any {@code LDClient} instance(s) that you have
   * already configured to use this {@code TestDataV2}. If no {@code LDClient} has been started yet,
   * it simply adds this flag to the test data which will be provided to any {@code LDClient} that
   * you subsequently configure.
   * <p>
   * Any subsequent changes to this {@link TestData.FlagBuilder} instance do not affect the test data,
   * unless you call {@link #update(TestData.FlagBuilder)} again.
   *
   * @param flagBuilder a flag configuration builder
   * @return the same {@code TestDataV2} instance
   * @see #flag(String)
   */
  public TestDataV2 update(TestData.FlagBuilder flagBuilder) {
    String key = flagBuilder.key;
    TestData.FlagBuilder clonedBuilder = new TestData.FlagBuilder(flagBuilder);
    ItemDescriptor newItem = null;

    synchronized (lock) {
      ItemDescriptor oldItem = currentFlags.get(key);
      int oldVersion = oldItem == null ? 0 : oldItem.getVersion();
      newItem = flagBuilder.createFlag(oldVersion + 1);
      currentFlags.put(key, newItem);
      currentBuilders.put(key, clonedBuilder);
    }

    pushToSynchronizers(FDv2SourceResult.changeSet(makePartialChangeSet(key, newItem), false));

    return this;
  }

  /**
   * Simulates a change in the synchronizer status.
   * <p>
   * Use this if you want to test the behavior of application code that uses
   * {@link com.launchdarkly.sdk.server.LDClient#getDataSourceStatusProvider()} to track whether the
   * synchronizer is having problems (for example, a network failure interrupting the streaming connection). It
   * does not actually stop the {@code TestDataV2} synchronizer from working, so even if you have simulated
   * an outage, calling {@link #update(TestData.FlagBuilder)} will still send updates.
   *
   * @param newState one of the constants defined by {@link com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider.State}
   * @param newError an {@link com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider.ErrorInfo} instance,
   *   or null
   * @return the same {@code TestDataV2} instance
   */
  public TestDataV2 updateStatus(DataSourceStatusProvider.State newState, DataSourceStatusProvider.ErrorInfo newError) {
    FDv2SourceResult statusResult;
    switch (newState) {
      case OFF:
        statusResult = newError != null
            ? FDv2SourceResult.terminalError(newError, false)
            : FDv2SourceResult.shutdown();
        break;
      case INTERRUPTED:
        statusResult = FDv2SourceResult.interrupted(
            newError != null ? newError : new DataSourceStatusProvider.ErrorInfo(DataSourceStatusProvider.ErrorKind.UNKNOWN, 0, null, Instant.now()),
            false);
        break;
      case VALID:
      case INITIALIZING:
      default:
        // VALID and INITIALIZING do not map to FDv2 status events (same as DataSourceSynchronizerAdapter)
        statusResult = null;
        break;
    }
    if (statusResult != null) {
      pushToSynchronizers(statusResult);
    }
    return this;
  }
  
  /**
   * Configures whether test data should be persisted to persistent stores.
   * <p>
   * By default, test data is persisted ({@code shouldPersist = true}) to maintain consistency with
   * previous versions' behavior. When {@code true}, the test data will be written to any configured persistent
   * store (if the store is in READ_WRITE mode). This is useful for integration tests that verify
   * your persistent store configuration.
   * <p>
   * Set this to {@code false} if you want to prevent test data from being written to persistent stores.
   * This may be appropriate for unit testing scenarios where you want to test your application logic
   * without affecting a persistent store.
   * <p>
   * Example:
   * <pre><code>
   *     TestDataV2 td = TestDataV2.synchronizer()
   *         .shouldPersist(false);  // Disable persistence to avoid polluting the store
   *     td.update(td.flag("flag-key").booleanFlag().variationForAllUsers(true));
   * </code></pre>
   *
   * @param shouldPersist true if test data should be persisted to persistent stores, false otherwise
   * @return the same {@code TestDataV2} instance
   */
  public TestDataV2 shouldPersist(boolean shouldPersist) {
    this.shouldPersist = shouldPersist;
    return this;
  }
  
  @Override
  public Synchronizer build(DataSourceBuildInputs context) {
    TestDataV2SynchronizerImpl synchronizer = new TestDataV2SynchronizerImpl();
    synchronized (lock) {
      synchronizerInstances.add(synchronizer);
    }
    return synchronizer;
  }

  private void pushToSynchronizers(FDv2SourceResult result) {
    for (TestDataV2SynchronizerImpl sync : synchronizerInstances) {
      CompletableFuture<Void> completion = new CompletableFuture<>();
      FDv2SourceResult wrappedResult = result.withCompletion(v -> {
        completion.complete(null);
        return null;
      });
      sync.put(wrappedResult, completion);
    }
  }
  
  private ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> makeFullChangeSet() {
    ImmutableMap<String, ItemDescriptor> copiedData;
    synchronized (lock) {
      copiedData = ImmutableMap.copyOf(currentFlags);
    }
    Iterable<Map.Entry<String, ItemDescriptor>> entries = copiedData.entrySet();
    return new ChangeSet<>(
        ChangeSetType.Full,
        Selector.EMPTY,
        Collections.singletonList(
            new AbstractMap.SimpleEntry<>(DataModel.FEATURES, new KeyedItems<>(entries))),
        null,
        shouldPersist);
  }

  private ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> makePartialChangeSet(String key, ItemDescriptor item) {
    return new ChangeSet<>(
        ChangeSetType.Partial,
        Selector.EMPTY,
        Collections.singletonList(
            new AbstractMap.SimpleEntry<>(DataModel.FEATURES, new KeyedItems<>(Collections.singletonList(new AbstractMap.SimpleEntry<>(key, item))))),
        null,
        shouldPersist);
  }
  
  private void closedSynchronizerInstance(TestDataV2SynchronizerImpl synchronizer) {
    synchronized (lock) {
      synchronizerInstances.remove(synchronizer);
    }
  }

  /**
   * Synchronizer implementation that queues initial and incremental change sets from TestDataV2.
   */
  private final class TestDataV2SynchronizerImpl implements Synchronizer {
    private final IterableAsyncQueue<FDv2SourceResult> resultQueue = new IterableAsyncQueue<>();
    private final CompletableFuture<FDv2SourceResult> shutdownFuture = new CompletableFuture<>();

    private final AtomicBoolean initialSent = new AtomicBoolean(false);

    void put(FDv2SourceResult result, CompletableFuture<Void> completion) {
      resultQueue.put(result);
      try {
        CompletableFuture.anyOf(completion, shutdownFuture).get();
      } catch (Exception e) {
        // Completion interrupted or canceled
      }
    }

    @Override
    public CompletableFuture<FDv2SourceResult> next() {
      if (!initialSent.getAndSet(true)) {
        // Send full changeset first, before any partial changesets that
        // accumulated from update()/delete() calls made before next() was first called.
        resultQueue.put(FDv2SourceResult.changeSet(makeFullChangeSet(), false));
      }
      return CompletableFuture.anyOf(shutdownFuture, resultQueue.take())
          .thenApply(r -> (FDv2SourceResult) r);
    }

    @Override
    public void close() {
      shutdownFuture.complete(FDv2SourceResult.shutdown());
      closedSynchronizerInstance(this);
    }
  }
}
