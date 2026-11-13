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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
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
 * A configured file can be a symbolic link. A mounted Kubernetes ConfigMap, for example, links each
 * file through a {@code ..data} link that is swapped to a new directory on every update, so the
 * update changes no entry that is named after a configured file. An event for any other entry in a
 * watched directory therefore counts when a configured file, read through any symbolic link, now
 * leads to another file, or its file's modification time or size changed. A busy sibling file costs
 * no reload. The directory of each configured file's final target is watched as well, so that a
 * change to a file that a configured link names in another directory is reported. That directory is
 * found again after every event, and one that is deleted, as the previous version of a ConfigMap is,
 * is dropped rather than registered again. A configured directory that is itself a symbolic link
 * and is pointed elsewhere, or a link further along the path that is pointed elsewhere while its old
 * target stays, is not followed; polling covers those deployments.
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
  private final Set<Path> configuredDirectories;
  private final long directoryRetryDelayNanos;
  private final LDLogger logger;
  private final Thread thread;
  private final AtomicBoolean stopped = new AtomicBoolean(false);
  private volatile Runnable onChange;

  // The directories that are not registered because they do not exist, and the time of the next
  // attempt to register them. The time comes from the monotonic clock, so a change to the system
  // clock does not move the schedule. Both are written during creation and then only by the
  // worker thread.
  private final Set<Path> missingDirectories = new HashSet<>();
  private long nextRetryAtNanos;

  // What the watcher last observed of each configured file, through any symbolic link: the file
  // that it finally leads to, and that file's state. The final targets that are not the configured
  // files themselves, and the directories holding them that are watched in addition to the
  // configured directories. All are written during creation and then only by the worker thread.
  private final Map<Path, Observation> observations = new HashMap<>();
  private final Set<Path> targetPaths = new HashSet<>();
  private final Set<Path> targetDirectories = new HashSet<>();

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
    FileDataWatcher watcher = new FileDataWatcher(ws, absoluteFilePaths, directoryPaths, directoryRetryDelay,
        logger);
    try {
      for (Path d : directoryPaths) {
        watcher.registerOrRememberAsMissing(d);
      }
      watcher.observeConfiguredFiles();
    } catch (RuntimeException e) {
      ws.close();
      throw e;
    }
    return watcher;
  }

  private FileDataWatcher(
      WatchService watchService,
      Set<Path> watchedFilePaths,
      Set<Path> configuredDirectories,
      Duration directoryRetryDelay,
      LDLogger logger
  ) {
    this.watchService = watchService;
    this.watchedFilePaths = watchedFilePaths;
    this.configuredDirectories = configuredDirectories;
    this.directoryRetryDelayNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(directoryRetryDelay.toMillis(), 1));
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
          long waitNanos = nextRetryAtNanos - System.nanoTime();
          key = waitNanos > 0 ? watchService.poll(waitNanos, TimeUnit.NANOSECONDS) : null;
        }
      } catch (InterruptedException e) {
        continue; // if stopped, the loop condition ends the thread
      } catch (ClosedWatchServiceException e) {
        return;
      }
      if (key != null) {
        processKey(key);
      }
      if (!missingDirectories.isEmpty() && System.nanoTime() - nextRetryAtNanos >= 0) {
        retryMissingDirectories();
      }
    }
  }

  private void processKey(WatchKey key) {
    boolean watchedFileWasChanged = false;
    boolean otherEntryChanged = false;
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
        if (watchedFilePaths.contains(absolutePath) || targetPaths.contains(absolutePath)) {
          watchedFileWasChanged = true;
          break;
        }
        otherEntryChanged = true;
      } else {
        otherEntryChanged = true;
      }
    }
    // Without the reset, the watch on this key stops working. The reset fails when the key is no
    // longer valid, which means that the directory was deleted. The watch on a configured directory
    // is gone, so the directory is registered again once it exists. The directory of a link's target
    // is not registered again: it is watched only while a configured link leads into it.
    if (!key.reset() && !stopped.get() && key.watchable() instanceof Path) {
      Path directory = (Path) key.watchable();
      if (configuredDirectories.contains(directory)) {
        logger.warn("Directory {} no longer exists. It is watched again once it appears.", directory);
        rememberAsMissing(directory);
      } else {
        targetDirectories.remove(directory);
        otherEntryChanged = true;
      }
    }
    // Observe the configured files after every event, so that what a later event is compared with
    // is current, and so that a link that now leads into another directory has it watched.
    boolean observedChange = observeConfiguredFiles();
    if ((watchedFileWasChanged || (otherEntryChanged && observedChange)) && !stopped.get()) {
      signal();
    }
  }

  // Observes every configured file through any symbolic link, and watches the directory of each final
  // target that is not watched yet. Returns true if a configured file now leads to another file, or
  // its file's state changed, since the last observation. Called during creation and then only by
  // the worker thread.
  private boolean observeConfiguredFiles() {
    boolean changed = false;
    Set<Path> targets = new HashSet<>();
    for (Path path : watchedFilePaths) {
      Observation observation = Observation.of(path);
      if (!observation.equals(observations.put(path, observation))) {
        changed = true;
      }
      if (observation.target == null || observation.target.equals(observation.unlinked)) {
        continue;
      }
      targets.add(observation.target);
      Path directory = observation.target.getParent();
      if (directory == null || configuredDirectories.contains(directory) || targetDirectories.contains(directory)) {
        continue;
      }
      try {
        directory.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
        targetDirectories.add(directory);
      } catch (IOException | ClosedWatchServiceException e) {
        // The link is still followed through the events in its own directory, and polling covers
        // the rest. Another observation tries again.
        logger.debug("Unable to watch directory {} of a link target: {}", directory, LogValues.exceptionSummary(e));
      }
    }
    targetPaths.clear();
    targetPaths.addAll(targets);
    return changed;
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
      nextRetryAtNanos = System.nanoTime() + directoryRetryDelayNanos;
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
    nextRetryAtNanos = System.nanoTime() + directoryRetryDelayNanos;
    if (registered && !stopped.get()) {
      observeConfiguredFiles();
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

  // One observation of a configured file. The target is the file that the path finally leads to
  // through any symbolic links, or null if it does not exist or cannot be resolved. The unlinked
  // path is the configured file in its directory's real location: a target equal to it means that
  // the file itself is not a link, even when a directory above it is. The state is that of the
  // target file.
  private static final class Observation {
    final Path target;
    final Path unlinked;
    final FileDataPoller.FileState state;

    private Observation(Path target, Path unlinked, FileDataPoller.FileState state) {
      this.target = target;
      this.unlinked = unlinked;
      this.state = state;
    }

    static Observation of(Path path) {
      Path target = null;
      Path unlinked = null;
      try {
        target = path.toRealPath();
        Path parent = path.getParent();
        unlinked = parent == null ? path : parent.toRealPath().resolve(path.getFileName());
      } catch (IOException | RuntimeException e) {
        // A file that does not exist, or a link that cannot be resolved, leads to no file.
      }
      return new Observation(target, unlinked, FileDataPoller.observe(path));
    }

    @Override
    public boolean equals(Object other) {
      if (other instanceof Observation) {
        Observation o = (Observation) other;
        return Objects.equals(target, o.target) && Objects.equals(state, o.state);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return Objects.hash(target, state);
    }
  }
}
