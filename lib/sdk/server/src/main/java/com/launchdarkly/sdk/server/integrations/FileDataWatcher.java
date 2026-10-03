package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.Watchable;
import java.time.Duration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

/**
 * Watches a set of files for changes on a worker thread and invokes a callback when one of them
 * is created, modified, or deleted.
 * <p>
 * The Java file system API reports changes at the directory level, so the watcher registers the
 * parent directory of every file. A file that does not exist yet is reported when it appears. A
 * directory that does not exist, or that is deleted while it is watched, is not an error. The
 * watcher tries to register it again on a fixed schedule until it exists, and then signals one
 * change so that a file written to it in the meantime is picked up.
 * <p>
 * The callback is invoked for every relevant notification, including the burst of notifications
 * that a single edit can produce, and once more right after the watcher starts so that a change
 * made between an initial load and the start of watching is not missed. Feed it into a
 * {@link FileDataReloader}, which coalesces the burst and skips no-op reloads.
 */
final class FileDataWatcher implements Closeable, Runnable {
  /**
   * The time between attempts to register a directory that cannot be watched because it does not
   * exist.
   */
  static final Duration DEFAULT_DIRECTORY_RETRY_DELAY = Duration.ofSeconds(1);

  private final WatchService watchService;
  private final Set<Path> watchedFilePaths;
  private final long directoryRetryDelayMillis;
  private final LDLogger logger;
  private final Thread thread;
  private final AtomicBoolean stopped = new AtomicBoolean(false);
  private volatile Runnable onChange;

  // The directories that are not registered because they do not exist, and the time of the next
  // attempt to register them. Both are written during creation and then only by the worker thread.
  private final Set<Path> missingDirectories = new HashSet<>();
  private long nextRetryAtMillis;

  /**
   * Creates a watcher for the given files. Nothing is watched until {@link #start(Runnable)}.
   *
   * @param filePaths the files to watch, absolute or relative to the working directory
   * @param logger the logger
   * @return the watcher
   * @throws IOException if the watch service cannot be created
   */
  static FileDataWatcher create(Iterable<Path> filePaths, LDLogger logger) throws IOException {
    return create(filePaths, DEFAULT_DIRECTORY_RETRY_DELAY, logger);
  }

  /**
   * Creates a watcher with a specific delay between attempts to register a directory that does not
   * exist. Visible for tests.
   *
   * @param filePaths the files to watch, absolute or relative to the working directory
   * @param directoryRetryDelay the time between registration attempts for a missing directory
   * @param logger the logger
   * @return the watcher
   * @throws IOException if the watch service cannot be created
   */
  static FileDataWatcher create(Iterable<Path> filePaths, Duration directoryRetryDelay, LDLogger logger)
      throws IOException {
    Set<Path> directoryPaths = new HashSet<>();
    Set<Path> absoluteFilePaths = new HashSet<>();
    for (Path p : filePaths) {
      Path absolutePath = p.toAbsolutePath().normalize();
      absoluteFilePaths.add(absolutePath);
      directoryPaths.add(absolutePath.getParent());
    }
    WatchService ws = FileSystems.getDefault().newWatchService();
    FileDataWatcher watcher = new FileDataWatcher(ws, absoluteFilePaths, directoryRetryDelay, logger);
    try {
      for (Path d : directoryPaths) {
        watcher.registerOrRememberAsMissing(d);
      }
    } catch (RuntimeException e) {
      ws.close();
      throw e;
    }
    return watcher;
  }

  private FileDataWatcher(
      WatchService watchService,
      Set<Path> watchedFilePaths,
      Duration directoryRetryDelay,
      LDLogger logger
  ) {
    this.watchService = watchService;
    this.watchedFilePaths = watchedFilePaths;
    this.directoryRetryDelayMillis = Math.max(directoryRetryDelay.toMillis(), 1);
    this.logger = logger;
    this.thread = new Thread(this, "LaunchDarkly-FileDataWatcher");
    this.thread.setDaemon(true);
  }

  /**
   * Starts the worker thread and then signals one change, because a file may have changed between
   * the caller's initial load and the registration of the watches.
   *
   * @param onChange called when a watched file changed
   */
  void start(Runnable onChange) {
    this.onChange = onChange;
    thread.start();
    signal();
  }

  @Override
  public void run() {
    while (!stopped.get()) {
      WatchKey key;
      try {
        if (missingDirectories.isEmpty()) {
          key = watchService.take(); // blocks until a change is available or the thread is interrupted
        } else {
          // Wake up for the next registration attempt even when no change arrives.
          long waitMillis = nextRetryAtMillis - System.currentTimeMillis();
          key = waitMillis > 0 ? watchService.poll(waitMillis, TimeUnit.MILLISECONDS) : null;
        }
      } catch (InterruptedException e) {
        continue; // if stopped, the loop condition ends the thread
      } catch (ClosedWatchServiceException e) {
        return;
      }
      if (key != null) {
        processKey(key);
      }
      if (!missingDirectories.isEmpty() && System.currentTimeMillis() >= nextRetryAtMillis) {
        retryMissingDirectories();
      }
    }
  }

  private void processKey(WatchKey key) {
    boolean watchedFileWasChanged = false;
    for (WatchEvent<?> event : key.pollEvents()) {
      if (event.kind() == OVERFLOW) {
        // Notifications were dropped, so a watched file may have changed.
        watchedFileWasChanged = true;
        break;
      }
      Watchable w = key.watchable();
      Object context = event.context();
      if (w instanceof Path && context instanceof Path) {
        Path absolutePath = ((Path) w).resolve((Path) context);
        if (watchedFilePaths.contains(absolutePath)) {
          watchedFileWasChanged = true;
          break;
        }
      }
    }
    // Without the reset, the watch on this key stops working. The reset fails when the key is no
    // longer valid, which means that the directory was deleted. The watch on it is gone, so the
    // directory is registered again once it exists.
    if (!key.reset() && !stopped.get() && key.watchable() instanceof Path) {
      Path directory = (Path) key.watchable();
      logger.warn("Directory {} no longer exists. It is watched again once it appears.", directory);
      rememberAsMissing(directory);
    }
    if (watchedFileWasChanged && !stopped.get()) {
      signal();
    }
  }

  // Registers a directory with the watch service. A directory that cannot be registered is
  // remembered as missing, so that the worker thread tries again on the retry schedule.
  private void registerOrRememberAsMissing(Path directory) {
    try {
      directory.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
    } catch (NoSuchFileException e) {
      logger.warn("Directory {} does not exist. It is watched once it appears.", directory);
      rememberAsMissing(directory);
    } catch (IOException e) {
      logger.warn("Unable to watch directory {}: {}. The attempt is repeated until it succeeds.", directory,
          LogValues.exceptionSummary(e));
      rememberAsMissing(directory);
    }
  }

  private void rememberAsMissing(Path directory) {
    if (missingDirectories.isEmpty()) {
      nextRetryAtMillis = System.currentTimeMillis() + directoryRetryDelayMillis;
    }
    missingDirectories.add(directory);
  }

  // Tries to register every missing directory. A directory that is registered leaves the missing
  // set, and one change is signalled so that files written to it while it was not watched are
  // picked up. A directory that still cannot be registered stays in the set without a new log
  // entry, because its absence was logged when it was first found missing.
  private void retryMissingDirectories() {
    boolean registered = false;
    for (Iterator<Path> it = missingDirectories.iterator(); it.hasNext();) {
      Path directory = it.next();
      try {
        directory.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
      } catch (IOException | ClosedWatchServiceException e) {
        continue;
      }
      logger.info("Directory {} exists. Watching it for changes.", directory);
      it.remove();
      registered = true;
    }
    nextRetryAtMillis = System.currentTimeMillis() + directoryRetryDelayMillis;
    if (registered && !stopped.get()) {
      signal();
    }
  }

  private void signal() {
    try {
      onChange.run();
    } catch (RuntimeException e) {
      // COVERAGE: there is no way to simulate this condition in a unit test
      logger.warn("Unexpected exception when reloading file data: {}", LogValues.exceptionSummary(e));
    }
  }

  /**
   * Waits for the worker thread to end after {@link #close()}. Visible for tests.
   *
   * @param timeoutMillis how long to wait
   * @return true if the worker thread has ended
   * @throws InterruptedException if the wait is interrupted
   */
  boolean awaitStop(long timeoutMillis) throws InterruptedException {
    thread.join(timeoutMillis);
    return !thread.isAlive();
  }

  /**
   * Stops watching and releases the watch service. It does not wait for the worker thread.
   */
  @Override
  public void close() {
    if (stopped.getAndSet(true)) {
      return;
    }
    thread.interrupt();
    try {
      watchService.close();
    } catch (IOException e) {
      // COVERAGE: there is no way to simulate this condition in a unit test
      logger.debug("Error closing file watch service: {}", LogValues.exceptionSummary(e));
    }
  }
}
