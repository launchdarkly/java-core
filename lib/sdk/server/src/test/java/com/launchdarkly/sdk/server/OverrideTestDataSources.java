package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.fdv2.ChangeSet;
import com.launchdarkly.sdk.fdv2.ChangeSetType;
import com.launchdarkly.sdk.fdv2.Selector;
import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;
import com.launchdarkly.sdk.server.datasources.Initializer;
import com.launchdarkly.sdk.server.datasources.Synchronizer;
import com.launchdarkly.sdk.server.subsystems.DataSourceBuilder;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * FDv2 data sources for tests of the override layer: an initializer that supplies fixed
 * LaunchDarkly data as a full transfer, and a synchronizer that never supplies anything so the
 * client never initializes.
 */
@SuppressWarnings("javadoc")
abstract class OverrideTestDataSources {
  private OverrideTestDataSources() {}

  /**
   * Returns an initializer that supplies the given data as a full transfer with a selector, which
   * is what makes the client report that it is initialized.
   */
  static DataSourceBuilder<Initializer> initializerWith(
      final Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> data) {
    return context -> new Initializer() {
      @Override
      public String name() {
        return "TestInitializer";
      }

      @Override
      public CompletableFuture<FDv2SourceResult> run() {
        ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> changeSet = new ChangeSet<>(
            ChangeSetType.Full, Selector.make(1, "test-state"), data, null, false);
        return CompletableFuture.completedFuture(FDv2SourceResult.changeSet(changeSet, false));
      }

      @Override
      public void close() {
      }
    };
  }

  /**
   * Returns a synchronizer that never delivers anything. With no sources at all the client would
   * consider itself initialized with empty data. A synchronizer that never delivers keeps the
   * client in its not-initialized state.
   */
  static DataSourceBuilder<Synchronizer> hangingSynchronizer() {
    return context -> new Synchronizer() {
      private final CompletableFuture<FDv2SourceResult> shutdown = new CompletableFuture<>();

      @Override
      public String name() {
        return "HangingSynchronizer";
      }

      @Override
      public CompletableFuture<FDv2SourceResult> next() {
        return shutdown;
      }

      @Override
      public void close() {
        shutdown.complete(FDv2SourceResult.shutdown());
      }
    };
  }
}
