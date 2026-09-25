package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.server.integrations.FileOverrides.ChangeDetection;
import com.launchdarkly.sdk.server.subsystems.ClientContext;
import com.launchdarkly.sdk.server.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.server.subsystems.OverrideSource;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Configures the file-based override source. Obtain an instance with {@link FileOverrides#source()},
 * call {@link #filePaths(String...)} or {@link #filePaths(Path...)} to specify the files, adjust
 * any other options, and pass the builder to
 * {@link DataSystemBuilder#overrides(ComponentConfigurer)}.
 * <p>
 * For more details, see {@link FileOverrides}.
 * <p>
 * Flag overrides are currently experimental and subject to change.
 *
 * @since 7.18.0
 */
public final class FileOverrideSourceBuilder implements ComponentConfigurer<OverrideSource> {
  private final List<Path> filePaths = new ArrayList<>();
  private FileData.DuplicateKeysHandling duplicateKeysHandling = FileData.DuplicateKeysHandling.FAIL;
  private ChangeDetection changeDetection = ChangeDetection.POLLING;
  private Duration pollInterval = FileOverrides.DEFAULT_POLL_INTERVAL;

  FileOverrideSourceBuilder() {}

  /**
   * Adds any number of files to load overrides from, specifying each file path as a string. The
   * order is significant: it determines which file wins under the duplicate keys handling when the
   * same key appears in more than one file.
   * <p>
   * Files are parsed as JSON if their first non-whitespace character is '{'. Otherwise, they are
   * parsed as YAML.
   *
   * @param filePaths path(s) to the file(s); may be absolute or relative to the current working directory
   * @return the same builder
   * @throws InvalidPathException if one of the parameters is not a valid file path
   */
  public FileOverrideSourceBuilder filePaths(String... filePaths) throws InvalidPathException {
    for (String p : filePaths) {
      this.filePaths.add(Paths.get(p));
    }
    return this;
  }

  /**
   * Adds any number of files to load overrides from, specifying each file path as a Path. The
   * order is significant: it determines which file wins under the duplicate keys handling when the
   * same key appears in more than one file.
   *
   * @param filePaths path(s) to the file(s); may be absolute or relative to the current working directory
   * @return the same builder
   */
  public FileOverrideSourceBuilder filePaths(Path... filePaths) {
    for (Path p : filePaths) {
      this.filePaths.add(p);
    }
    return this;
  }

  /**
   * Specifies how to handle the same key appearing in more than one file.
   * <p>
   * With {@link FileData.DuplicateKeysHandling#FAIL}, the default, a reload fails if the same flag or
   * segment key appears in more than one file, and the previously loaded overrides stay in effect.
   * With {@link FileData.DuplicateKeysHandling#IGNORE}, the entry from the first configured file that
   * defines the key is kept and the others are discarded. A null value selects the default.
   *
   * @param duplicateKeysHandling specifies how to handle duplicate keys
   * @return the same builder
   */
  public FileOverrideSourceBuilder duplicateKeysHandling(FileData.DuplicateKeysHandling duplicateKeysHandling) {
    this.duplicateKeysHandling = duplicateKeysHandling == null
        ? FileData.DuplicateKeysHandling.FAIL : duplicateKeysHandling;
    return this;
  }

  /**
   * Selects how the source detects file changes. The default is {@link ChangeDetection#POLLING}.
   * The two modes are alternatives, so setting one replaces the other.
   *
   * @param changeDetection the change detection mode
   * @return the same builder
   */
  public FileOverrideSourceBuilder changeDetection(ChangeDetection changeDetection) {
    this.changeDetection = changeDetection;
    return this;
  }

  /**
   * Sets the interval between examinations of the files in {@link ChangeDetection#POLLING} mode.
   * {@link ChangeDetection#WATCHING} mode ignores it. The default is
   * {@link FileOverrides#DEFAULT_POLL_INTERVAL}. An interval below
   * {@link FileOverrides#MINIMUM_POLL_INTERVAL} is raised to the minimum.
   *
   * @param pollInterval the polling interval
   * @return the same builder
   */
  public FileOverrideSourceBuilder pollInterval(Duration pollInterval) {
    this.pollInterval = pollInterval;
    return this;
  }

  /**
   * Called internally by the SDK to create the override source.
   *
   * @param context the client context
   * @return the override source
   * @throws IllegalArgumentException if no file paths were specified, or the change detection mode
   *   or the poll interval is null
   */
  @Override
  public OverrideSource build(ClientContext context) {
    if (filePaths.isEmpty()) {
      throw new IllegalArgumentException("no file paths were specified for the file-based override source");
    }
    if (changeDetection == null) {
      throw new IllegalArgumentException("a change detection mode is required for the file-based override source");
    }
    if (pollInterval == null) {
      throw new IllegalArgumentException("a poll interval is required for the file-based override source");
    }
    List<Path> absolutePaths = new ArrayList<>(filePaths.size());
    for (Path p : filePaths) {
      absolutePaths.add(p.toAbsolutePath().normalize());
    }

    LDLogger logger = context.getBaseLogger().subLogger("FileOverrideSource");

    Duration effectivePollInterval = pollInterval;
    if (changeDetection == ChangeDetection.POLLING && pollInterval.compareTo(FileOverrides.MINIMUM_POLL_INTERVAL) < 0) {
      logger.warn("Poll interval {} is below the minimum; using {}", pollInterval, FileOverrides.MINIMUM_POLL_INTERVAL);
      effectivePollInterval = FileOverrides.MINIMUM_POLL_INTERVAL;
    }

    return new FileOverrideSourceImpl(absolutePaths, duplicateKeysHandling, changeDetection, effectivePollInterval, logger);
  }
}
