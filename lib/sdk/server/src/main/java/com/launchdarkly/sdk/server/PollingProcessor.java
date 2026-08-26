package com.launchdarkly.sdk.server;

import com.google.common.annotations.VisibleForTesting;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.internal.http.FailureClass;
import com.launchdarkly.sdk.internal.http.HttpErrors;
import com.launchdarkly.sdk.internal.http.HttpErrors.HttpErrorException;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider.ErrorInfo;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider.ErrorKind;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider.State;
import com.launchdarkly.sdk.server.subsystems.DataSource;
import com.launchdarkly.sdk.server.subsystems.DataSourceUpdateSink;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.FullDataSet;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.SerializationException;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class PollingProcessor implements DataSource {
  private static final String ERROR_CONTEXT_MESSAGE = "on polling request";
  private static final String WILL_RETRY_MESSAGE = "will retry at next scheduled poll interval";
  static final Duration DEFAULT_EXTENDED_INITIAL_DELAY = Duration.ofMinutes(5);

  @VisibleForTesting final FeatureRequestor requestor;
  private final DataSourceUpdateSink dataSourceUpdates;
  private final ScheduledExecutorService scheduler;
  @VisibleForTesting final Duration pollInterval;
  private final PollingStrategy strategy;
  private final AtomicBoolean initialized = new AtomicBoolean(false);
  // task tracks the currently pending poll; null when we haven't started yet
  // or when we've been closed.
  private ScheduledFuture<?> task;
  // isClosed is set once in close().
  private volatile boolean isClosed = false;
  private final CompletableFuture<Void> initFuture;
  private final LDLogger logger;

  PollingProcessor(
      FeatureRequestor requestor,
      DataSourceUpdateSink dataSourceUpdates,
      ScheduledExecutorService sharedExecutor,
      Duration pollInterval,
      Duration extendedInitialDelay,
      LDLogger logger
      ) {
    this.requestor = requestor; // note that HTTP configuration is applied to the requestor when it is created
    this.dataSourceUpdates = dataSourceUpdates;
    this.scheduler = sharedExecutor;
    this.pollInterval = pollInterval;
    this.strategy = new PollingStrategy(pollInterval, extendedInitialDelay);
    this.initFuture = new CompletableFuture<>();
    this.logger = logger;
  }

  @Override
  public boolean isInitialized() {
    return initialized.get();
  }

  @Override
  public void close() throws IOException {
    synchronized (this) {
      if (isClosed) {
        return;
      }
      isClosed = true;
      if (task != null) {
        task.cancel(true);
        task = null;
      }
    }
    logger.info("Closing LaunchDarkly PollingProcessor");
    requestor.close();
    dataSourceUpdates.updateStatus(State.OFF, null);
  }

  @Override
  public Future<Void> start() {
    synchronized (this) {
      if (!isClosed && task == null) {
        logger.info("Starting LaunchDarkly polling client with interval: {} milliseconds",
            pollInterval.toMillis());
        task = scheduler.schedule(this::poll, 0L, TimeUnit.MILLISECONDS);
      }
    }
    return initFuture;
  }

  private void scheduleNext(Duration delay) {
    synchronized (this) {
      if (isClosed) {
        return;
      }
      task = scheduler.schedule(this::poll, delay.toMillis(), TimeUnit.MILLISECONDS);
    }
  }

  private void poll() {
    try {
      // If we already obtained data earlier, and the poll request returns a cached response, then we don't
      // want to bother parsing the data or reinitializing the data store. But if we never succeeded in
      // storing any data, then we would still want to parse and try to store it even if it's cached.
      boolean alreadyInited = initialized.get();
      FullDataSet<ItemDescriptor> allData = requestor.getAllData(!alreadyInited);
      if (allData == null) {
        // This means it was cached, and alreadyInited was true
        dataSourceUpdates.updateStatus(State.VALID, null);
      } else {
        if (dataSourceUpdates.init(allData)) {
          dataSourceUpdates.updateStatus(State.VALID, null);
          if (!initialized.getAndSet(true)) {
            logger.info("Initialized LaunchDarkly client."); 
            initFuture.complete(null);
          }
        }
      }
      strategy.onSuccess();
    } catch (HttpErrorException e) {
      FailureClass failureClass = HttpErrors.classifyAndLogHttpFailure(
          logger, e.getStatus(), ERROR_CONTEXT_MESSAGE, WILL_RETRY_MESSAGE);
      dataSourceUpdates.updateStatus(State.INTERRUPTED, ErrorInfo.fromHttpError(e.getStatus()));
      if (strategy.onFailure(failureClass)) {
        logger.info("Classified failure as UNEXPECTED; engaging extended backoff.");
      }
    } catch (IOException e) {
      FailureClass failureClass = HttpErrors.classifyAndLogTransportFailure(
          logger, e, ERROR_CONTEXT_MESSAGE, WILL_RETRY_MESSAGE);
      dataSourceUpdates.updateStatus(State.INTERRUPTED, ErrorInfo.fromException(ErrorKind.NETWORK_ERROR, e));
      if (strategy.onFailure(failureClass)) {
        logger.info("Classified failure as UNEXPECTED; engaging extended backoff.");
      }
    } catch (SerializationException e) {
      logger.error("Polling request received malformed data: {}", e.toString());
      dataSourceUpdates.updateStatus(State.INTERRUPTED, ErrorInfo.fromException(ErrorKind.INVALID_DATA, e));
      strategy.onFailure(FailureClass.NORMAL);
    } catch (Exception e) {
      logger.error("Unexpected error from polling processor: {}", e.toString());
      logger.debug(e.toString(), e);
      dataSourceUpdates.updateStatus(State.INTERRUPTED, ErrorInfo.fromException(ErrorKind.UNKNOWN, e));
      strategy.onFailure(FailureClass.NORMAL);
    } finally {
      // Regardless of poll outcome, schedule the next attempt per strategy.
      Duration wait = strategy.nextWait();
      scheduleNext(wait);
    }
  }
}
