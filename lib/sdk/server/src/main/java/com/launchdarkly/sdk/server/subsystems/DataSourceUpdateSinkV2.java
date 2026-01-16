package com.launchdarkly.sdk.server.subsystems;

import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider.ErrorInfo;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider.State;
import com.launchdarkly.sdk.server.interfaces.DataStoreStatusProvider;

/**
 * Interfaces required by data source updates implementations in FDv2.
 * <p>
 * This interface extends {@link TransactionalDataSourceUpdateSink} to add status tracking
 * and status update capabilities required for FDv2 data sources.
 * <p>
 * This interface is not stable, and not subject to any backwards compatibility guarantees or semantic versioning.
 * It is in early access. If you want access to this feature please join the EAP. https://launchdarkly.com/docs/sdk/features/data-saving-mode
 * 
 * @since 5.0.0
 * @see TransactionalDataSourceUpdateSink
 * @see DataSource
 */
public interface DataSourceUpdateSinkV2 extends TransactionalDataSourceUpdateSink {
  /**
   * An object that provides status tracking for the data store, if applicable.
   * <p>
   * This may be useful if the data source needs to be aware of storage problems that might require it
   * to take some special action: for instance, if a database outage may have caused some data to be
   * lost and therefore the data should be re-requested from LaunchDarkly.
   * 
   * @return a {@link DataStoreStatusProvider}
   */
  DataStoreStatusProvider getDataStoreStatusProvider();

  /**
   * Informs the SDK of a change in the data source's status.
   * <p>
   * Data source implementations should use this method if they have any concept of being in a valid
   * state, a temporarily disconnected state, or a permanently stopped state.
   * <p>
   * If {@code newState} is different from the previous state, and/or {@code newError} is non-null, the
   * SDK will start returning the new status (adding a timestamp for the change) from
   * {@link DataSourceStatusProvider#getStatus()}, and will trigger status change events to any
   * registered listeners.
   * <p>
   * A special case is that if {@code newState} is {@link State#INTERRUPTED},
   * but the previous state was {@link State#INITIALIZING}, the state will
   * remain at {@link State#INITIALIZING} because {@link State#INTERRUPTED}
   * is only meaningful after a successful startup.
   * 
   * @param newState the data source state
   * @param newError information about a new error, if any
   * @see DataSourceStatusProvider
   */
  void updateStatus(State newState, ErrorInfo newError);
}

