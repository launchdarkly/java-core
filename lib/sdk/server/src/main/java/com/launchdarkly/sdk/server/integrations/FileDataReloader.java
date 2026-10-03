package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;
import com.launchdarkly.sdk.server.integrations.FileDataSourceParsing.FileDataException;
import com.launchdarkly.sdk.server.integrations.OverrideFileLoader.LoadResult;

import java.io.Closeable;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the reload cycle for the files of an override source. It serializes reloads, debounces
 * change signals, retains the last good result on failure by not calling the apply callback,
 * retries after failures, and skips applications whose content did not change.
 * <p>
 * The reload work itself is delegated to a {@link Loader}. The loader reads every configured file
 * and returns the merged result, or throws {@link FileDataException} when a file cannot be read or
 * parsed or when the files cannot be merged.
 * <p>
 * The file data source does not use this class. It keeps its own reload behavior.
 */
final class FileDataReloader implements Closeable {
  /**
   * A settle window long enough to coalesce the burst of change notifications produced by a
   * single file edit, and short enough to stay responsive.
   */
  static final Duration DEFAULT_DEBOUNCE_DELAY = Duration.ofMillis(100);

  /**
   * Bounds how long a failed reload can go uncorrected when no further change notification
   * arrives, for example when the failure came from reading a file mid-write. Reading a local
   * file is cheap, so this can be short.
   */
  static final Duration DEFAULT_RETRY_DELAY = Duration.ofSeconds(1);

  /**
   * Performs one full load of all configured files.
   */
  interface Loader {
    LoadResult load() throws FileDataException;
  }

  /**
   * Receives the outcome of each reload.
   * <p>
   * Both methods are called with the reload lock held, so calls never overlap with each other
   * or with another reload. Neither method may call back into {@link FileDataReloader#close()}.
   */
  interface Handler {
    /**
     * Called with each successfully merged result. An exception thrown here fails the reload: it is
     * reported through {@link #onError(FileDataException)}, nothing from the load is remembered as
     * the last good result, and the reload is retried.
     *
     * @param result the merged data
     */
    void apply(LoadResult result);

    /**
     * Called when a reload fails, including when {@link #apply(LoadResult)} throws, once per
     * distinct failure. With automatic retries, repeats of an identical failure do not call this
     * again. A success re-arms it. The reloader logs failures itself, so implementations only need
     * to update their own state.
     *
     * @param e the failure
     */
    void onError(FileDataException e);
  }

  private final Loader loader;
  private final Handler handler;
  private final LDLogger logger;
  private final long debounceDelayMillis;
  private final long retryDelayMillis;
  private final boolean skipUnchanged;

  // Serializes the load work between reloadNow and the worker thread.
  private final Object reloadLock = new Object();
  private byte[] lastGoodHash = null;
  private String lastErrorMessage = null;

  // Guards the worker and the two timers.
  private final Object timerLock = new Object();
  private ScheduledThreadPoolExecutor worker = null;
  private ScheduledFuture<?> debounceFuture = null;
  private ScheduledFuture<?> retryFuture = null;

  private final AtomicBoolean closed = new AtomicBoolean(false);

  /**
   * Creates a reloader. The caller performs the initial load with {@link #reloadNow()}, routes
   * change signals to {@link #trigger()}, and calls {@link #close()} when finished.
   * <p>
   * The worker thread starts on the first {@link #trigger()} call or the first failed load that
   * arms a retry, not here. Construction alone does not start a thread.
   *
   * @param loader performs each full load
   * @param handler receives results and failures
   * @param logger receives log output about reloads and failures
   * @param debounceDelay how long to wait after a trigger for further triggers to settle before
   *   reloading. Zero or negative reloads on every trigger without waiting.
   * @param retryDelay how long to wait after a failed reload before retrying without a trigger.
   *   Zero or negative disables the automatic retry.
   * @param skipUnchanged if true, a successful load whose raw file contents are identical to the
   *   last applied contents does not call the apply callback
   */
  FileDataReloader(
      Loader loader,
      Handler handler,
      LDLogger logger,
      Duration debounceDelay,
      Duration retryDelay,
      boolean skipUnchanged
  ) {
    this.loader = loader;
    this.handler = handler;
    this.logger = logger;
    this.debounceDelayMillis = debounceDelay == null ? 0 : debounceDelay.toMillis();
    this.retryDelayMillis = retryDelay == null ? 0 : retryDelay.toMillis();
    this.skipUnchanged = skipUnchanged;
  }

  /**
   * Synchronously loads the files and applies the result or reports the failure. Use it for the
   * initial load. A failure here arms the same automatic retry as a failed triggered reload.
   */
  void reloadNow() {
    if (!reload() && retryDelayMillis > 0) {
      armRetry();
    }
  }

  /**
   * Signals that the files may have changed and a reload should happen after the debounce delay.
   * It never blocks. Signals that arrive while a reload is already pending extend the settle
   * window, so a burst of signals produces one reload after the burst ends.
   */
  void trigger() {
    synchronized (timerLock) {
      if (closed.get()) {
        return;
      }
      ScheduledThreadPoolExecutor executor = ensureWorker();
      if (debounceFuture != null) {
        debounceFuture.cancel(false);
      }
      debounceFuture = executor.schedule(() -> runReload(false), Math.max(debounceDelayMillis, 0),
          TimeUnit.MILLISECONDS);
    }
  }

  /**
   * Stops the reloader. It does not wait for a reload that is already in progress. A reload
   * wedged in a blocking file read must not be able to wedge shutdown. Such a reload may still
   * deliver its result shortly after close returns, and consumers tolerate that. A reload that
   * has not yet reached its callbacks when close is called does not invoke them.
   */
  @Override
  public void close() {
    if (closed.getAndSet(true)) {
      return;
    }
    synchronized (timerLock) {
      if (debounceFuture != null) {
        debounceFuture.cancel(false);
        debounceFuture = null;
      }
      if (retryFuture != null) {
        retryFuture.cancel(false);
        retryFuture = null;
      }
      if (worker != null) {
        worker.shutdownNow();
      }
    }
  }

  /**
   * Reports whether the worker thread has been started. Visible for tests.
   *
   * @return true if a worker exists
   */
  boolean hasWorker() {
    synchronized (timerLock) {
      return worker != null;
    }
  }

  /**
   * Formats a failure for logging. The exception's own description requires a cause, and a
   * duplicate key failure has none, so this builds the text from the parts that are present.
   *
   * @param e the failure
   * @return the message, followed by the cause in brackets when there is one
   */
  static String describe(FileDataException e) {
    StringBuilder s = new StringBuilder();
    if (e.getMessage() != null) {
      s.append(e.getMessage());
    }
    if (e.getCause() != null) {
      if (s.length() > 0) {
        s.append(" ");
      }
      s.append("[").append(e.getCause().toString()).append("]");
    }
    return s.toString();
  }

  private ScheduledThreadPoolExecutor ensureWorker() {
    if (worker == null) {
      ThreadFactory threadFactory = runnable -> {
        Thread t = new Thread(runnable, "LaunchDarkly-FileDataReloader");
        t.setDaemon(true);
        return t;
      };
      ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, threadFactory);
      executor.setRemoveOnCancelPolicy(true);
      executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
      worker = executor;
    }
    return worker;
  }

  private void armRetry() {
    synchronized (timerLock) {
      if (closed.get()) {
        return;
      }
      // An already armed retry keeps its earlier deadline.
      if (retryFuture != null && !retryFuture.isDone()) {
        return;
      }
      retryFuture = ensureWorker().schedule(() -> runReload(true), retryDelayMillis, TimeUnit.MILLISECONDS);
    }
  }

  // Runs on the worker thread for a debounced or a retried reload.
  private void runReload(boolean isRetry) {
    try {
      if (isRetry) {
        logger.debug("Retrying flag data load after earlier failure");
      } else {
        logger.info("Reloading flag data after detecting a change");
      }
      synchronized (timerLock) {
        // This reload supersedes a pending retry. It either succeeds, or it fails and arms a fresh
        // retry below.
        if (retryFuture != null) {
          retryFuture.cancel(false);
          retryFuture = null;
        }
      }
      if (!reload() && retryDelayMillis > 0) {
        armRetry();
      }
    } catch (RuntimeException e) {
      // The executor would otherwise swallow the exception and the worker would go quiet.
      logger.error("Unexpected error while reloading flag data: {}", LogValues.exceptionSummary(e));
      logger.debug(LogValues.exceptionTrace(e));
    }
  }

  // Performs one full load of all configured files. It reports whether the load succeeded, which
  // decides whether a retry gets armed. A skipped no-op application counts as success. The whole
  // set is re-read on every reload because entries are combined across files in order, so a
  // change to one file can alter which file wins for a key.
  private boolean reload() {
    synchronized (reloadLock) {
      // A trigger already queued when close was called can still reach here.
      if (closed.get()) {
        return true;
      }

      LoadResult result;
      try {
        result = loader.load();
      } catch (FileDataException e) {
        return fail(e);
      }

      // Close may have happened while the files were being read. Deliver nothing in that case.
      if (closed.get()) {
        return true;
      }

      // A success right after a failure must apply even when the content is unchanged since the
      // last success. The consumer heard about the failure and may have moved to an interrupted
      // state. Only apply tells it that things are good again.
      boolean recovering = lastErrorMessage != null;
      byte[] hash = result.getContentHash();
      if (skipUnchanged && !recovering && hash != null && Arrays.equals(hash, lastGoodHash)) {
        return true;
      }
      // Nothing is remembered until the consumer has accepted the result. A result that the
      // consumer rejects must not become the baseline that skip-unchanged compares against, and
      // must not count as a recovery.
      try {
        handler.apply(result);
      } catch (RuntimeException e) {
        return fail(new FileDataException("unable to apply flag data", e));
      }
      lastErrorMessage = null;
      lastGoodHash = hash;
      return true;
    }
  }

  private boolean fail(FileDataException e) {
    // Close may have happened while the files were being read. Deliver nothing and report success
    // so that no retry is armed.
    if (closed.get()) {
      return true;
    }
    // With automatic retries, a persistent failure would repeat the same log entry and the same
    // callback on every attempt. Repeats of an identical failure are logged at debug level and do
    // not call onError again. A consumer therefore sees one report per distinct failure.
    String description = describe(e);
    if (description.equals(lastErrorMessage)) {
      logger.debug("Unable to load flags: {}", description);
      return false;
    }
    lastErrorMessage = description;
    logger.error("Unable to load flags: {}", description);
    handler.onError(e);
    return false;
  }
}
