package sdktest;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.server.interfaces.FlagChangeListener;
import com.launchdarkly.sdk.server.interfaces.FlagTracker;
import com.launchdarkly.sdk.server.interfaces.FlagValueChangeListener;

import org.slf4j.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import sdktest.Representations.FlagChangeNotification;
import sdktest.Representations.FlagValueChangeNotification;

/**
 * The flag change listeners that the test harness has registered with one SDK client, keyed by
 * the listener ID the harness assigned. Each listener posts a notification to its callback URI
 * whenever the SDK fires it. Registering an ID that is already in use replaces the earlier
 * listener.
 */
final class ListenerRegistry {
  private final FlagTracker tracker;
  private final Logger logger;
  private final Map<String, FlagChangeListener> listeners = new HashMap<>();

  ListenerRegistry(FlagTracker tracker, Logger logger) {
    this.tracker = tracker;
    this.logger = logger;
  }

  /**
   * Registers a general flag change listener. Every flag change event the SDK delivers is posted
   * as a notification carrying the flag key.
   */
  synchronized void registerFlagChangeListener(String listenerId, URI callbackUri) {
    HookCallbackService callback = new HookCallbackService(callbackUri);
    FlagChangeListener listener = event -> {
      FlagChangeNotification notification = new FlagChangeNotification();
      notification.listenerId = listenerId;
      notification.flagKey = event.getKey();
      post(callback, listenerId, notification);
    };
    tracker.addFlagChangeListener(listener);
    store(listenerId, listener);
  }

  /**
   * Registers a flag value change listener for one flag and context. The SDK re-evaluates the flag
   * on each change and fires only when the value differs; the notification carries both values.
   * The Java SDK's API takes no application default value, so the harness's default is accepted
   * but not used.
   */
  synchronized void registerFlagValueChangeListener(String listenerId, String flagKey, LDContext context,
      URI callbackUri) {
    HookCallbackService callback = new HookCallbackService(callbackUri);
    FlagValueChangeListener valueListener = event -> {
      FlagValueChangeNotification notification = new FlagValueChangeNotification();
      notification.listenerId = listenerId;
      notification.flagKey = event.getKey();
      notification.oldValue = event.getOldValue();
      notification.newValue = event.getNewValue();
      post(callback, listenerId, notification);
    };
    FlagChangeListener subscription = tracker.addFlagValueChangeListener(flagKey, context, valueListener);
    store(listenerId, subscription);
  }

  /**
   * Removes a listener so that it posts no further notifications.
   *
   * @return false if no listener has that ID
   */
  synchronized boolean unregister(String listenerId) {
    FlagChangeListener listener = listeners.remove(listenerId);
    if (listener == null) {
      return false;
    }
    tracker.removeFlagChangeListener(listener);
    return true;
  }

  /**
   * Removes every listener. Called when the SDK client entity closes.
   */
  synchronized void closeAll() {
    for (FlagChangeListener listener : new ArrayList<>(listeners.values())) {
      tracker.removeFlagChangeListener(listener);
    }
    listeners.clear();
  }

  private void store(String listenerId, FlagChangeListener listener) {
    FlagChangeListener previous = listeners.put(listenerId, listener);
    if (previous != null) {
      tracker.removeFlagChangeListener(previous);
    }
  }

  // The SDK calls listeners from a worker thread. A callback failure must not propagate into the
  // SDK's dispatch, so it is logged instead.
  private void post(HookCallbackService callback, String listenerId, Object notification) {
    try {
      callback.post(notification);
    } catch (RuntimeException e) {
      logger.warn("Listener {} could not post its notification: {}", listenerId, e.toString());
    }
  }
}
