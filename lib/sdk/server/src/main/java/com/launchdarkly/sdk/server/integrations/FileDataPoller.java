package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Detects changes to a set of files by examining them on a fixed interval. Use it where file
 * system change notifications are not available or not reliable, alone or together with them.
 * A change to the modification time or the size of any file invokes the change callback. A file
 * that appears or disappears is also a change. A file whose attributes cannot be read counts as
 * absent.
 * <p>
 * The poller samples the files once per interval and compares only the modification time and the
 * size. A rewrite that keeps both values is not detected.
 * <p>
 * Detection is generous. The callback can run for a change that does not alter the effective
 * data. Feed it into a {@link FileDataReloader}, whose debouncing and skip-unchanged handling
 * absorb the excess.
 */
final class FileDataPoller implements Closeable {
  private final List<Path> paths;
  private final Runnable onChange;
  private final LDLogger logger;
  private final ScheduledThreadPoolExecutor executor;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private List<FileState> last;

  /**
   * Creates a started poller. It examines the files once before it returns, so only later changes
   * invoke the callback. Call {@link #close()} to stop it.
   *
   * @param paths the files to examine
   * @param interval the time between examinations
   * @param onChange called when any file changed since the previous examination
   * @param logger receives log output about a callback that fails
   */
  FileDataPoller(List<Path> paths, Duration interval, Runnable onChange, LDLogger logger) {
    this.paths = new ArrayList<>(paths);
    this.onChange = onChange;
    this.logger = logger;
    this.last = observeAll(this.paths);
    ThreadFactory threadFactory = runnable -> {
      Thread t = new Thread(runnable, "LaunchDarkly-FileDataPoller");
      t.setDaemon(true);
      return t;
    };
    this.executor = new ScheduledThreadPoolExecutor(1, threadFactory);
    this.executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    long millis = Math.max(interval.toMillis(), 1);
    this.executor.scheduleWithFixedDelay(this::examine, millis, millis, TimeUnit.MILLISECONDS);
  }

  /**
   * Stops the poller. It does not wait for an examination or a callback that is in progress. A
   * file system that does not respond must not block shutdown. As a result, the callback can run
   * one more time shortly after close returns. Consumers tolerate a late call, as they do for a
   * late reload.
   */
  @Override
  public void close() {
    if (closed.getAndSet(true)) {
      return;
    }
    executor.shutdownNow();
  }

  private void examine() {
    if (closed.get()) {
      return;
    }
    List<FileState> current = observeAll(paths);
    boolean changed = !current.equals(last);
    last = current;
    if (changed && !closed.get()) {
      try {
        onChange.run();
      } catch (RuntimeException e) {
        // The executor would otherwise cancel the repeating task and the poller would go quiet.
        logger.error("Unexpected error while handling a file change: {}", LogValues.exceptionSummary(e));
        logger.debug(LogValues.exceptionTrace(e));
      }
    }
  }

  static List<FileState> observeAll(List<Path> paths) {
    List<FileState> states = new ArrayList<>(paths.size());
    for (Path path : paths) {
      states.add(observe(path));
    }
    return states;
  }

  private static FileState observe(Path path) {
    try {
      BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
      return new FileState(true, attributes.lastModifiedTime().toMillis(), attributes.size());
    } catch (IOException | RuntimeException e) {
      return FileState.ABSENT;
    }
  }

  /**
   * The observed state of one file, or its absence.
   */
  static final class FileState {
    static final FileState ABSENT = new FileState(false, 0, 0);

    final boolean exists;
    final long modifiedTimeMillis;
    final long size;

    FileState(boolean exists, long modifiedTimeMillis, long size) {
      this.exists = exists;
      this.modifiedTimeMillis = modifiedTimeMillis;
      this.size = size;
    }

    @Override
    public boolean equals(Object other) {
      if (other instanceof FileState) {
        FileState o = (FileState) other;
        return exists == o.exists && modifiedTimeMillis == o.modifiedTimeMillis && size == o.size;
      }
      return false;
    }

    @Override
    public int hashCode() {
      return Objects.hash(exists, modifiedTimeMillis, size);
    }
  }
}
