package com.launchdarkly.sdk.server;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.internal.events.EventFlushListener;
import com.launchdarkly.sdk.internal.events.EventFlushResult;
import com.launchdarkly.sdk.server.integrations.DataSourceStatusContext;
import com.launchdarkly.sdk.server.integrations.EventFlushContext;
import com.launchdarkly.sdk.server.integrations.Hook;
import com.launchdarkly.sdk.server.integrations.InitializationContext;
import com.launchdarkly.sdk.server.integrations.InitializerContext;
import com.launchdarkly.sdk.server.integrations.SynchronizerChangeContext;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;

import java.io.Closeable;
import java.time.Duration;
import java.util.List;

/**
 * Delivers handler invocations to the registered hooks in registration order. An exception from a
 * hook is logged so that the operation that produced the invocation completes unaffected.
 */
final class HookRunner implements DataSourceLifecycleListener, EventFlushListener, Closeable {
  private final List<Hook> hooks;
  private final LDLogger logger;

  HookRunner(List<Hook> hooks, LDLogger logger) {
    this.hooks = hooks;
    this.logger = logger;
  }

  @Override
  public void dataSourceStatusChanged(DataSourceStatusProvider.Status previous, DataSourceStatusProvider.Status current) {
    DataSourceStatusContext statusContext = new DataSourceStatusContext(previous, current);
    for (Hook hook : hooks) {
      try {
        hook.dataSourceStatusChanged(statusContext);
      } catch (Exception e) {
        logError("a data source status change", "dataSourceStatusChanged", hook, e);
      }
    }
  }

  @Override
  public void initializerCompleted(InitializerContext initializerContext) {
    for (Hook hook : hooks) {
      try {
        hook.initializerCompleted(initializerContext);
      } catch (Exception e) {
        logError("initialization", "initializerCompleted", hook, e);
      }
    }
  }

  @Override
  public void synchronizerChanged(SynchronizerChangeContext changeContext) {
    for (Hook hook : hooks) {
      try {
        hook.synchronizerChanged(changeContext);
      } catch (Exception e) {
        logError("a synchronizer change", "synchronizerChanged", hook, e);
      }
    }
  }

  @Override
  public void initializationCompleted(InitializationContext initializationContext) {
    for (Hook hook : hooks) {
      try {
        hook.initializationCompleted(initializationContext);
      } catch (Exception e) {
        logError("initialization", "initializationCompleted", hook, e);
      }
    }
  }

  @Override
  public void flushCompleted(EventFlushResult result) {
    EventFlushContext flushContext = new EventFlushContext(
        result.getEventCount(),
        result.getPayloadBytes(),
        result.isSuccess(),
        result.getStatusCode(),
        Duration.ofMillis(result.getDurationMillis()),
        result.getDroppedCount());
    for (Hook hook : hooks) {
      try {
        hook.eventFlushCompleted(flushContext);
      } catch (Exception e) {
        logError("event delivery", "eventFlushCompleted", hook, e);
      }
    }
  }

  /**
   * Closes every hook that implements {@link Closeable}. The client calls this last, after the data
   * system and the event processor have delivered their final invocations.
   */
  @Override
  public void close() {
    for (Hook hook : hooks) {
      if (hook instanceof Closeable) {
        try {
          ((Closeable) hook).close();
        } catch (Exception e) {
          logError("close", "close", hook, e);
        }
      }
    }
  }

  private void logError(String operation, String handler, Hook hook, Exception e) {
    logger.error("During {}, handler \"{}\" of hook \"{}\" reported error: {}",
        operation, handler, hook.getMetadata().getName(), e.toString());
  }
}
