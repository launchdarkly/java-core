package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.server.interfaces.FlagChangeEvent;
import com.launchdarkly.sdk.server.interfaces.FlagChangeListener;

/**
 * Internal facade that wraps DataSourceUpdatesImpl to provide flag change notifications.
 * <p>
 * This class is package-private and should not be used by application code.
 */
final class FlagChangedFacade implements FlagChangeNotifier {
  private final DataSourceUpdatesImpl dataSourceUpdates;

  FlagChangedFacade(DataSourceUpdatesImpl dataSourceUpdates) {
    this.dataSourceUpdates = dataSourceUpdates;
  }

  @Override
  public void addFlagChangeListener(FlagChangeListener listener) {
    dataSourceUpdates.addFlagChangeListener(listener);
  }

  @Override
  public void removeFlagChangeListener(FlagChangeListener listener) {
    dataSourceUpdates.removeFlagChangeListener(listener);
  }
}

