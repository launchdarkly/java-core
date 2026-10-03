package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.server.integrations.FileDataSourceParsing.FileDataException;
import com.launchdarkly.sdk.server.integrations.FileOverrides.ChangeDetection;
import com.launchdarkly.sdk.server.integrations.OverrideFileLoader.FileSummary;
import com.launchdarkly.sdk.server.integrations.OverrideFileLoader.LoadResult;
import com.launchdarkly.sdk.server.subsystems.OverrideSink;
import com.launchdarkly.sdk.server.subsystems.OverrideSource;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The file-based override source. It reads one or more files in the file data source document
 * format, merges them in the configured order, and supplies the result to the override sink as
 * one snapshot. It reloads when the files change, by polling or by watching.
 * <p>
 * A configured file that does not exist contributes no overrides. A file that exists but cannot
 * be read or parsed fails that reload: the sink is not called, so the last good overrides stay in
 * effect, and the reload is retried.
 */
final class FileOverrideSourceImpl implements OverrideSource {
  private final List<Path> paths;
  private final ChangeDetection changeDetection;
  private final Duration pollInterval;
  private final LDLogger logger;
  private final OverrideFileLoader loader;

  private volatile FileDataReloader reloader;
  private volatile FileDataPoller poller;
  private volatile FileDataWatcher watcher;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  FileOverrideSourceImpl(
      List<Path> paths,
      FileData.DuplicateKeysHandling duplicateKeysHandling,
      ChangeDetection changeDetection,
      Duration pollInterval,
      LDLogger logger
  ) {
    this.paths = new ArrayList<>(paths);
    this.changeDetection = changeDetection;
    this.pollInterval = pollInterval;
    this.logger = logger;
    this.loader = new OverrideFileLoader(paths, duplicateKeysHandling);
  }

  /**
   * Performs the initial load synchronously, so overrides present in the files are in effect when
   * the client constructor returns, then starts change detection. A file that does not exist yet
   * contributes no overrides. A file that cannot be read or parsed is not fatal: the client runs
   * with the last good overrides, the failure is logged, and the retry, plus the change signal,
   * recovers once the file is readable.
   */
  @Override
  public void start(OverrideSink sink) {
    reloader = new FileDataReloader(
        loader::load,
        new FileDataReloader.Handler() {
          @Override
          public void apply(LoadResult result) {
            sink.setOverrides(result.getData());
            logOverridesInEffect(result);
          }

          @Override
          public void onError(FileDataException e) {
            // The reloader has logged the failure. The last good overrides stay in effect.
          }
        },
        logger,
        FileDataReloader.DEFAULT_DEBOUNCE_DELAY,
        FileDataReloader.DEFAULT_RETRY_DELAY,
        true
    );
    reloader.reloadNow();

    if (closed.get()) {
      return;
    }
    switch (changeDetection) {
    case WATCHING:
      try {
        FileDataWatcher w = FileDataWatcher.create(paths, logger);
        watcher = w;
        w.start(reloader::trigger);
      } catch (IOException e) {
        // COVERAGE: constructing a watcher only fails under unusual OS conditions
        logger.error("Unable to watch override files: {}", e.toString());
      }
      break;
    case POLLING:
    default:
      poller = new FileDataPoller(paths, pollInterval, reloader::trigger, logger);
      break;
    }
  }

  /**
   * Returns the interval between file examinations in polling mode. Visible for tests.
   *
   * @return the effective poll interval
   */
  Duration getPollInterval() {
    return pollInterval;
  }

  // Reports the overrides now in effect and the file each came from. The reloader applies a
  // snapshot only when the content changed, so this logs each change once.
  private void logOverridesInEffect(LoadResult result) {
    List<String> details = new ArrayList<>(result.getFiles().size());
    for (FileSummary file : result.getFiles()) {
      if (!file.isPresent()) {
        details.add(file.getPath() + ": absent");
      } else if (file.getFlags() == 0 && file.getSegments() == 0) {
        details.add(file.getPath() + ": no entries");
      } else {
        details.add(file.getPath() + ": " + countsText(file.getFlags(), file.getSegments()));
      }
    }
    String fileDetails = String.join("; ", details);
    if (result.getFlagCount() == 0 && result.getSegmentCount() == 0) {
      logger.info("Flag overrides: none in effect ({})", fileDetails);
      return;
    }
    logger.info("Flag overrides in effect: {} ({})", countsText(result.getFlagCount(), result.getSegmentCount()),
        fileDetails);
  }

  // Formats flag and segment counts, for example "2 flags, 1 segment".
  static String countsText(int flags, int segments) {
    List<String> parts = new ArrayList<>(2);
    if (flags > 0) {
      parts.add(pluralize(flags, "flag"));
    }
    if (segments > 0) {
      parts.add(pluralize(segments, "segment"));
    }
    return String.join(", ", parts);
  }

  private static String pluralize(int count, String noun) {
    return count == 1 ? "1 " + noun : count + " " + noun + "s";
  }

  @Override
  public void close() {
    if (closed.getAndSet(true)) {
      return;
    }
    FileDataWatcher w = watcher;
    if (w != null) {
      w.close();
    }
    FileDataPoller p = poller;
    if (p != null) {
      p.close();
    }
    FileDataReloader r = reloader;
    if (r != null) {
      r.close();
    }
  }
}
