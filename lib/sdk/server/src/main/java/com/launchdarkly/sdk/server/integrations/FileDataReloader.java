package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;
import com.launchdarkly.sdk.server.integrations.FileDataSourceParsing.FileDataException;
import com.launchdarkly.sdk.server.integrations.OverrideFileLoader.LoadResult;

import java.io.Closeable;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
 * A file whose content is in effect and is found empty may be in the middle of an in-place rewrite.
 * The loader can hold such a file back with {@link EmptiedFileException}: the reloader keeps the
 * current data, reports no failure, and reads the files again when the exception says.
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
    /**
     * Reads every configured file and merges the entries.
     *
     * @param retryDelay the reloader's retry delay, or zero if it does not retry. A loader may hold
     *   an empty file back for a time based on it, because a retry reads the files again. With zero,
     *   nothing would read them again, so it must not hold any back.
     * @return the merged result
     * @throws FileDataException if a file cannot be read or parsed, or the files cannot be merged
     * @throws EmptiedFileException if a file was found empty and is held back
     */
    LoadResult load(Duration retryDelay) throws FileDataException, EmptiedFileException;

    /**
     * Called with the result of a load that is now the data in effect: it was applied, or it was
     * skipped because it is unchanged. It is called with the reload lock held.
     *
     * @param result the result
     */
    void accepted(LoadResult result);
  }

  /**
   * Thrown by a {@link Loader} that holds back files it found empty. A file may be in the middle of an
   * in-place rewrite: a writer that truncates the file and then writes the new content.
   */
  @SuppressWarnings("serial")
  static final class EmptiedFileException extends Exception {
    private final List<Path> paths;
    private final long retryAfterNanos;

    EmptiedFileException(List<Path> paths, long retryAfterNanos) {
      super("files " + paths + " are empty");
      this.paths = Collections.unmodifiableList(new ArrayList<>(paths));
      this.retryAfterNanos = retryAfterNanos;
    }

    /**
     * The files held back, in configuration order.
     */
    List<Path> getPaths() {
      return paths;
    }

    /**
     * How long to wait before loading again, in nanoseconds.
     */
    long getRetryAfterNanos() {
      return retryAfterNanos;
    }
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
     * to update their own state. A load that defers an empty file and reads it again later is not a
     * failure and does not call this.
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
  // What reload() returns when no retry is needed.
  private static final long NO_RETRY = -1;
  private final boolean skipUnchanged;

  // Serializes the load work between reloadNow and the worker thread.
  private final Object reloadLock = new Object();
  private byte[] lastGoodHash = null;
  private String lastErrorMessage = null;
  // True from a load that found an emptied file until the next load starts. Written inside the
  // reload lock. It only chooses the log message of the retry that such a load arms.
  private volatile boolean readAgainPending = false;

  // Guards the worker and the two timers.
  private final Object timerLock = new Object();
  private ScheduledThreadPoolExecutor worker = null;
  private ScheduledFuture<?> debounceFuture = null;
  private ScheduledFuture<?> retryFuture = null;
  // The delay of the latest request to arm a retry, in nanoseconds, or -1 before the first. Visible for
  // tests through getLastRequestedRetryNanos.
  private volatile long lastRequestedRetryNanos = -1;

  private final AtomicBoolean closed = new AtomicBoolean(false);

  /**
   * Creates a reloader. The caller performs the initial load with {@link #reloadNow()}, routes
   * change signals to {@link #trigger()}, and calls {@link #close()} when finished.
   * <p>
   * The worker thread starts on the first {@link #trigger()} call or the first load that arms a
   * retry, not here. Construction alone does not start a thread.
   *
   * @param loader performs each full load
   * @param handler receives results and failures
   * @param logger receives log output about reloads and failures
   * @param debounceDelay how long to wait after a trigger for further triggers to settle before
   *   reloading. Zero or negative reloads on every trigger without waiting.
   * @param retryDelay how long to wait after a failed reload before retrying without a trigger. A
   *   load that holds back an empty file says itself when to load again. Zero or negative disables
   *   the automatic retry, and the loader then parses an empty file as it is.
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
    long retryAfterNanos = reload();
    if (retryAfterNanos != NO_RETRY) {
      armRetry(retryAfterNanos);
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
   * Returns the delay of the latest request to arm a retry, whether or not it armed one. Visible for
   * tests.
   *
   * @return the delay in nanoseconds, or -1 if no retry was requested
   */
  long getLastRequestedRetryNanos() {
    return lastRequestedRetryNanos;
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

  private void armRetry(long delayNanos) {
    lastRequestedRetryNanos = delayNanos;
    synchronized (timerLock) {
      if (closed.get()) {
        return;
      }
      // An already armed retry keeps its earlier deadline.
      if (retryFuture != null && !retryFuture.isDone()) {
        return;
      }
      retryFuture = ensureWorker().schedule(() -> runReload(true), delayNanos, TimeUnit.NANOSECONDS);
    }
  }

  // Runs on the worker thread for a debounced or a retried reload.
  private void runReload(boolean isRetry) {
    try {
      if (isRetry) {
        logger.debug(readAgainPending ? "Reading flag data again after a file was found empty"
            : "Retrying flag data load after earlier failure");
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
      long retryAfterNanos = reload();
      if (retryAfterNanos != NO_RETRY) {
        armRetry(retryAfterNanos);
      }
    } catch (RuntimeException e) {
      // The executor would otherwise swallow the exception and the worker would go quiet.
      logger.error("Unexpected error while reloading flag data: {}", LogValues.exceptionSummary(e));
      logger.debug(LogValues.exceptionTrace(e));
    }
  }

  // Performs one full load of all configured files. It returns when to load again: NO_RETRY after a
  // success, including a skipped no-op application, the retry delay after a failure, or the time that
  // a load that holds back an empty file asks for. The whole set is re-read on every reload because
  // entries are combined across files in order, so a change to one file can alter which file wins
  // for a key.
  private long reload() {
    synchronized (reloadLock) {
      // A trigger already queued when close was called can still reach here.
      if (closed.get()) {
        return NO_RETRY;
      }
      readAgainPending = false;

      LoadResult result;
      try {
        result = loader.load(retryDelayMillis > 0 ? Duration.ofMillis(retryDelayMillis) : Duration.ZERO);
      } catch (EmptiedFileException e) {
        return readAgainLater(e);
      } catch (FileDataException e) {
        return fail(e);
      }

      // Close may have happened while the files were being read. Deliver nothing in that case.
      if (closed.get()) {
        return NO_RETRY;
      }

      // A success right after a failure must apply even when the content is unchanged since the
      // last success. The consumer heard about the failure and may have moved to an interrupted
      // state. Only apply tells it that things are good again.
      boolean recovering = lastErrorMessage != null;
      byte[] hash = result.getContentHash();
      if (skipUnchanged && !recovering && hash != null && Arrays.equals(hash, lastGoodHash)) {
        loader.accepted(result);
        return NO_RETRY;
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
      loader.accepted(result);
      return NO_RETRY;
    }
  }

  // Called inside the reload lock. The loader held back files that it found empty: a file whose
  // content is in effect may be in the middle of an in-place rewrite, a writer that truncates the file
  // and then writes the new content. Whatever the files' format, the load keeps the current data and
  // arms the retry without reporting a failure, for the time that the loader asks for. The change
  // signal for the write starts a load sooner. The loader decides how long a file can be held back, and
  // holds nothing back when retries are off, because nothing would read the file again. A closed
  // reloader arms no retry.
  private long readAgainLater(EmptiedFileException e) {
    for (Path path : e.getPaths()) {
      logger.debug("File {} is empty, possibly while it is being rewritten; reading it again before applying it", path);
    }
    readAgainPending = true;
    return e.getRetryAfterNanos();
  }

  // Reports a failure and returns when to retry.
  private long fail(FileDataException e) {
    // Close may have happened while the files were being read. Deliver nothing and arm no retry.
    if (closed.get()) {
      return NO_RETRY;
    }
    long retryAfterNanos = retryDelayMillis > 0 ? TimeUnit.MILLISECONDS.toNanos(retryDelayMillis) : NO_RETRY;
    // With automatic retries, a persistent failure would repeat the same log entry and the same
    // callback on every attempt. Repeats of an identical failure are logged at debug level and do
    // not call onError again. A consumer therefore sees one report per distinct failure.
    String description = describe(e);
    if (description.equals(lastErrorMessage)) {
      logger.debug("Unable to load flags: {}", description);
      return retryAfterNanos;
    }
    lastErrorMessage = description;
    logger.error("Unable to load flags: {}", description);
    handler.onError(e);
    return retryAfterNanos;
  }
}
