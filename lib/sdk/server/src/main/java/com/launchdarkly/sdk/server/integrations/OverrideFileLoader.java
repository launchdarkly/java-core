package com.launchdarkly.sdk.server.integrations;

import com.google.common.collect.ImmutableList;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.integrations.FileDataReloader.EmptiedFileException;
import com.launchdarkly.sdk.server.integrations.FileDataSourceParsing.FileDataException;
import com.launchdarkly.sdk.server.integrations.FileDataSourceParsing.FlagFactory;
import com.launchdarkly.sdk.server.integrations.FileDataSourceParsing.FlagFileParser;
import com.launchdarkly.sdk.server.integrations.FileDataSourceParsing.FlagFileRep;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

import static com.launchdarkly.sdk.server.DataModel.FEATURES;
import static com.launchdarkly.sdk.server.DataModel.SEGMENTS;

/**
 * Reads the files of the file-based override source and merges them into one snapshot.
 * <p>
 * This loader is separate from the file data source's loader because the override source has
 * different rules: a configured file that does not exist contributes no entries, entries keep the
 * versions that the documents specify, a value-only entry becomes a flag that is on and serves the
 * value by fallthrough, the format of a file is chosen by its extension, and every failure to read or
 * parse a file is reported as a file data error. The file data source keeps its own behavior.
 * <p>
 * Whether a file with no content is valid depends on its format, like the rest of its content. A JSON
 * file with no content is not a JSON document, so it fails to parse. A YAML file with no content is a
 * document with no entries.
 * <p>
 * A file that is rewritten in place is empty for a moment, and a load can find it so. The loader
 * remembers, for each file, whether it contributed content to the data in effect, and since when it
 * has been found empty. A file whose content is in effect and is found empty is deferred, not parsed:
 * {@link #load(Duration)} still reads the other files, so that files emptied together are deferred
 * from the same load, and then throws {@link EmptiedFileException}. The data in effect stays, and the
 * reloader reads the files again after its retry delay. The file is parsed once it has stayed empty,
 * without being written, for about one retry delay, or once it has been found empty for five retry
 * delays in all, so that a file that keeps being rewritten cannot hold its change back for longer. A file that
 * never contributed content, such as a placeholder, and a file whose empty state is in effect are
 * parsed right away. The reloader calls {@link #load(Duration)} and {@link #accepted(LoadResult)} one
 * call at a time, so this state needs no synchronization.
 */
final class OverrideFileLoader implements FileDataReloader.Loader {
  /**
   * How many retry delays, counted from when a file was first found empty, a file that keeps being
   * found empty can be deferred for.
   */
  static final int MAX_DEFERRAL_RETRY_DELAYS = 5;

  /**
   * Describes one configured file after a load.
   */
  static final class FileSummary {
    private final Path path;
    private final boolean present;
    private final boolean hasContent;
    private final int flags;
    private final int segments;

    FileSummary(Path path, boolean present, boolean hasContent, int flags, int segments) {
      this.path = path;
      this.present = present;
      this.hasContent = hasContent;
      this.flags = flags;
      this.segments = segments;
    }

    Path getPath() {
      return path;
    }

    /**
     * False when the file does not exist.
     */
    boolean isPresent() {
      return present;
    }

    /**
     * False when the file does not exist, or is empty or white space only.
     */
    boolean hasContent() {
      return hasContent;
    }

    /**
     * The number of flag entries the merge kept from this file. An entry dropped by the duplicate keys
     * handling is not counted.
     */
    int getFlags() {
      return flags;
    }

    /**
     * The number of segment entries the merge kept from this file.
     */
    int getSegments() {
      return segments;
    }
  }

  /**
   * The merged result of one full load.
   */
  static final class LoadResult {
    private final Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> data;
    private final List<FileSummary> files;
    private final int flagCount;
    private final int segmentCount;
    private final byte[] contentHash;

    LoadResult(
        Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> data,
        List<FileSummary> files,
        int flagCount,
        int segmentCount,
        byte[] contentHash
    ) {
      this.data = data;
      this.files = Collections.unmodifiableList(new ArrayList<>(files));
      this.flagCount = flagCount;
      this.segmentCount = segmentCount;
      this.contentHash = contentHash;
    }

    Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> getData() {
      return data;
    }

    /**
     * One summary per configured file, in configuration order.
     */
    List<FileSummary> getFiles() {
      return files;
    }

    int getFlagCount() {
      return flagCount;
    }

    int getSegmentCount() {
      return segmentCount;
    }

    /**
     * A digest of the raw content of every file that was read. Two loads with an equal digest produced
     * the same data. The digest may be null when the runtime cannot compute one.
     */
    byte[] getContentHash() {
      return contentHash;
    }
  }

  /**
   * The time during which a file whose content is in effect has been found empty.
   */
  private static final class EmptyEpisode {
    // When the file was first found empty, and when it was last found empty after a write; from the
    // monotonic clock.
    final long firstSeenNanos;
    long sinceNanos;
    // The modification time of the file when it was last found empty after a write, or null if it
    // could not be read. It is only compared for equality, never with the clock.
    FileTime modified;

    EmptyEpisode(long nowNanos, FileTime modified) {
      this.firstSeenNanos = nowNanos;
      this.sinceNanos = nowNanos;
      this.modified = modified;
    }
  }

  private final List<Path> paths;
  private final FileData.DuplicateKeysHandling duplicateKeysHandling;
  private final LongSupplier nanoClock;
  // Whether each configured file contributed content to the data in effect. Updated only by
  // accepted(LoadResult). A file that is not listed did not.
  private final Map<Path, Boolean> appliedHadContent = new HashMap<>();
  // The files whose content is in effect and that are being found empty.
  private final Map<Path, EmptyEpisode> emptyEpisodes = new HashMap<>();

  /**
   * Creates a loader.
   *
   * @param paths the files to read, in precedence order
   * @param duplicateKeysHandling what to do when the same key appears in more than one file
   */
  OverrideFileLoader(List<Path> paths, FileData.DuplicateKeysHandling duplicateKeysHandling) {
    this(paths, duplicateKeysHandling, System::nanoTime);
  }

  /**
   * Creates a loader with a specific monotonic clock. Visible for tests.
   */
  OverrideFileLoader(List<Path> paths, FileData.DuplicateKeysHandling duplicateKeysHandling,
      LongSupplier nanoClock) {
    this.paths = new ArrayList<>(paths);
    this.duplicateKeysHandling = duplicateKeysHandling;
    this.nanoClock = nanoClock;
  }

  /**
   * Reads every configured file in order and merges the entries.
   *
   * @param retryDelay the reloader's retry delay, or zero if it does not retry. A file whose content is
   *   in effect and that is found empty, or white space only, is deferred while it has stayed empty
   *   without being written for less than nine tenths of this, and has been found empty for less
   *   than {@link #MAX_DEFERRAL_RETRY_DELAYS} times this. With zero, every file is parsed as it is.
   * @return the merged result with a summary of each file
   * @throws FileDataException if a file that exists cannot be read or parsed, or the files cannot be
   *   merged, also when another file is deferred
   * @throws EmptiedFileException if a file is deferred and every other file loads
   */
  @Override
  public LoadResult load(Duration retryDelay) throws FileDataException, EmptiedFileException {
    MessageDigest digest = newDigest();
    Map<DataKind, Map<String, ItemDescriptor>> data = new HashMap<>();
    List<FileSummary> files = new ArrayList<>(paths.size());
    List<Path> deferred = new ArrayList<>();
    for (Path path : paths) {
      byte[] raw;
      try {
        raw = Files.readAllBytes(path);
      } catch (NoSuchFileException e) {
        // Absence is a valid state, not a failure.
        emptyEpisodes.remove(path);
        files.add(new FileSummary(path, false, false, 0, 0));
        continue;
      } catch (IOException e) {
        throw new FileDataException("file " + path + ": unable to read file", e);
      }
      boolean hasContent = hasContent(raw);
      if (hasContent) {
        emptyEpisodes.remove(path);
      } else if (deferEmptyFile(path, retryDelay)) {
        // The other files are still read, so that each emptied file starts its delay in this load, and
        // a failure of another file still fails the load.
        deferred.add(path);
        continue;
      }
      // One read feeds both the digest and the parse, so the skip-unchanged digest can never disagree
      // with the content that was applied.
      if (digest != null) {
        digest.update(raw);
        digest.update((byte) 0);
      }
      int flagsAdded = 0;
      int segmentsAdded = 0;
      try {
        FlagFileParser parser = FlagFileParser.selectForPath(path);
        FlagFileRep fileContents = parser.parse(new ByteArrayInputStream(raw));
        if (fileContents.flags != null) {
          for (Map.Entry<String, LDValue> e : fileContents.flags.entrySet()) {
            if (add(data, FEATURES, e.getKey(), FlagFactory.flagFromJson(e.getValue()))) {
              flagsAdded++;
            }
          }
        }
        if (fileContents.flagValues != null) {
          for (Map.Entry<String, LDValue> e : fileContents.flagValues.entrySet()) {
            if (add(data, FEATURES, e.getKey(), FlagFactory.flagWithValue(e.getKey(), e.getValue(), 0))) {
              flagsAdded++;
            }
          }
        }
        if (fileContents.segments != null) {
          for (Map.Entry<String, LDValue> e : fileContents.segments.entrySet()) {
            if (add(data, SEGMENTS, e.getKey(), FlagFactory.segmentFromJson(e.getValue()))) {
              segmentsAdded++;
            }
          }
        }
      } catch (FileDataException e) {
        throw new FileDataException("file " + path + ": " + e.getMessage(), e.getCause());
      } catch (IOException e) {
        throw new FileDataException("file " + path + ": cannot read document", e);
      } catch (RuntimeException e) {
        // A document can be valid JSON or YAML and still hold a flag or segment that the data model
        // cannot accept. For the override source that is a parse failure of the file, so the last good
        // overrides stay in effect.
        throw new FileDataException("file " + path + ": cannot parse flag or segment data", e);
      }
      files.add(new FileSummary(path, true, hasContent, flagsAdded, segmentsAdded));
    }

    if (!deferred.isEmpty()) {
      throw new EmptiedFileException(deferred);
    }

    ImmutableList.Builder<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> all = ImmutableList.builder();
    for (Map.Entry<DataKind, Map<String, ItemDescriptor>> e : data.entrySet()) {
      all.add(new AbstractMap.SimpleEntry<>(e.getKey(), new KeyedItems<>(ImmutableList.copyOf(e.getValue().entrySet()))));
    }
    Map<String, ItemDescriptor> flags = data.get(FEATURES);
    Map<String, ItemDescriptor> segments = data.get(SEGMENTS);
    return new LoadResult(all.build(), files, flags == null ? 0 : flags.size(),
        segments == null ? 0 : segments.size(), digest == null ? null : digest.digest());
  }

  /**
   * Records that the result of a load is now the data in effect: it was applied, or it was skipped
   * because it is unchanged. Whether each file contributed content to the data in effect decides
   * whether finding it empty later defers it.
   *
   * @param result the result of a load by this loader
   */
  @Override
  public void accepted(LoadResult result) {
    for (FileSummary file : result.getFiles()) {
      appliedHadContent.put(file.getPath(), file.hasContent());
    }
  }

  // Decides whether a file that was read empty is deferred. A file whose content is not in effect is
  // parsed right away: a placeholder that never had content, or a file whose empty state was applied.
  // Otherwise the file may be in the middle of an in-place rewrite. The episode starts when the file
  // is first found empty, and a new modification time means a new rewrite, which starts the grace
  // again. The file is deferred within nine tenths of a retry delay of the latest start of the grace,
  // and within the cap of the first time it was found empty. The retry that a deferral arms comes one
  // retry delay later, and a timer can fire slightly early, so the slack lets that retry parse a file
  // that has stayed empty instead of deferring it for a second retry delay. The episode is kept when
  // the file is parsed, so later loads keep parsing it until it changes. A zero retry delay, which
  // means that the reloader does not retry, gives no grace, so nothing is deferred: nothing would read
  // the file again.
  private boolean deferEmptyFile(Path path, Duration retryDelay) {
    if (!Boolean.TRUE.equals(appliedHadContent.get(path))) {
      return false;
    }
    long now = nanoClock.getAsLong();
    FileTime modified = modifiedTime(path);
    EmptyEpisode episode = emptyEpisodes.get(path);
    if (episode == null) {
      episode = new EmptyEpisode(now, modified);
      emptyEpisodes.put(path, episode);
    } else if (!Objects.equals(modified, episode.modified)) {
      episode.sinceNanos = now;
      episode.modified = modified;
    }
    long delay = retryDelay.toNanos();
    return now - episode.sinceNanos < delay - delay / 10
        && now - episode.firstSeenNanos < MAX_DEFERRAL_RETRY_DELAYS * delay;
  }

  private static FileTime modifiedTime(Path path) {
    try {
      return Files.getLastModifiedTime(path);
    } catch (IOException e) {
      return null;
    }
  }

  // A file with only white space, or a byte order mark, has no content.
  private static boolean hasContent(byte[] raw) {
    String text = new String(raw, StandardCharsets.UTF_8);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (!Character.isWhitespace(c) && c != '\uFEFF') {
        return true;
      }
    }
    return false;
  }

  // Adds an entry unless the key was already added. Reports whether it added the entry.
  private boolean add(Map<DataKind, Map<String, ItemDescriptor>> data, DataKind kind, String key, ItemDescriptor item)
      throws FileDataException {
    Map<String, ItemDescriptor> items = data.computeIfAbsent(kind, k -> new HashMap<>());
    if (items.containsKey(key)) {
      if (duplicateKeysHandling == FileData.DuplicateKeysHandling.IGNORE) {
        return false;
      }
      throw new FileDataException("in " + kind.getName() + ", key \"" + key + "\" was already defined", null);
    }
    items.put(key, item);
    return true;
  }

  private static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      // COVERAGE: every Java runtime provides SHA-256
      return null;
    }
  }
}
