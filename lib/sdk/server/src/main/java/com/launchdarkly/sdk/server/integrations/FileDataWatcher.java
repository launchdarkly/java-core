package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.Watchable;
import java.util.HashSet;
import java.util.Set;
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
 * parent directory of every file. A file that does not exist yet is reported when it appears, as
 * long as its directory exists when the watcher is created. A directory that does not exist is an
 * error from {@link #create(Iterable, LDLogger)}.
 * <p>
 * The callback is invoked for every relevant notification, including the burst of notifications
 * that a single edit can produce, and once more right after the watcher starts so that a change
 * made between an initial load and the start of watching is not missed. Feed it into a
 * {@link FileDataReloader}, which coalesces the burst and skips no-op reloads.
 */
final class FileDataWatcher implements Closeable, Runnable {
  private final WatchService watchService;
  private final Set<Path> watchedFilePaths;
  private final LDLogger logger;
  private final Thread thread;
  private final AtomicBoolean stopped = new AtomicBoolean(false);
  private volatile Runnable onChange;

  /**
   * Creates a watcher for the given files. Nothing is watched until {@link #start(Runnable)}.
   *
   * @param filePaths the files to watch, absolute or relative to the working directory
   * @param logger the logger
   * @return the watcher
   * @throws IOException if the watch service cannot be created or a parent directory cannot be
   *   registered, for example because it does not exist
   */
  static FileDataWatcher create(Iterable<Path> filePaths, LDLogger logger) throws IOException {
    Set<Path> directoryPaths = new HashSet<>();
    Set<Path> absoluteFilePaths = new HashSet<>();
    for (Path p : filePaths) {
      Path absolutePath = p.toAbsolutePath().normalize();
      absoluteFilePaths.add(absolutePath);
      directoryPaths.add(absolutePath.getParent());
    }
    WatchService ws = FileSystems.getDefault().newWatchService();
    try {
      for (Path d : directoryPaths) {
        d.register(ws, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
      }
    } catch (IOException | RuntimeException e) {
      ws.close();
      throw e;
    }
    return new FileDataWatcher(ws, absoluteFilePaths, logger);
  }

  private FileDataWatcher(WatchService watchService, Set<Path> watchedFilePaths, LDLogger logger) {
    this.watchService = watchService;
    this.watchedFilePaths = watchedFilePaths;
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
        key = watchService.take(); // blocks until a change is available or the thread is interrupted
      } catch (InterruptedException e) {
        continue; // if stopped, the loop condition ends the thread
      } catch (ClosedWatchServiceException e) {
        return;
      }
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
      key.reset(); // without this, the watch on this key stops working
      if (watchedFileWasChanged && !stopped.get()) {
        signal();
      }
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
