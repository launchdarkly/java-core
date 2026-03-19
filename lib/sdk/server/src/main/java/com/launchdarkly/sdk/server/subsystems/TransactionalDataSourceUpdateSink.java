package com.launchdarkly.sdk.server.subsystems;

import com.launchdarkly.sdk.fdv2.ChangeSet;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;

import java.util.Map;

/**
 * Interface that an implementation of {@link DataSource} will use to push data into the SDK transactionally.
 * <p>
 * The data source interacts with this object, rather than manipulating the data store directly, so
 * that the SDK can perform any other necessary operations that must happen when data is updated. This
 * object also provides a mechanism to report status changes.
 * <p>
 * Component factories for {@link DataSource} implementations will receive an implementation of this
 * interface in the {@link ClientContext#getDataSourceUpdateSink()} property of {@link ClientContext}.
 * <p>
 * This interface is not stable, and not subject to any backwards compatibility guarantees or semantic versioning.
 * It is in early access. If you want access to this feature please join the EAP. https://launchdarkly.com/docs/sdk/features/data-saving-mode
 * 
 * @since 5.0.0
 * @see DataSource
 * @see ClientContext
 */
public interface TransactionalDataSourceUpdateSink {
  /**
   * Apply the given change set to the store. This should be done atomically if possible.
   * 
   * @param changeSet the changeset to apply
   * @return true if the update succeeded, false if it failed
   */
  boolean apply(ChangeSet<Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>>> changeSet);
}
