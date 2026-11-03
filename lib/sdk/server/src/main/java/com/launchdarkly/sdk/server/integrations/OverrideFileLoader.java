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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * A file that is rewritten in place is empty for a moment, and a load can find it so. A file counts
 * as empty when it holds only white space, or when its parser reads its content as no document, as the
 * YAML parser does for a part of a file that holds only a comment or a document marker. The loader
 * remembers, for each file, whether it contributed content to the data in effect, and since when it
 * has been found empty without being written. A file whose content is in effect and that is found
 * empty is held back, not parsed: {@link #load(Duration)} still reads the other files, so that files
 * emptied together are held back from the same load, and then throws {@link EmptiedFileException}.
 * The data in effect stays, and the reloader reads the files again when the exception says. A file is
 * held back until it has stayed empty, without being written, for one retry delay. Loads stop holding
 * files back once they have held some back for five retry delays in a row, so that files that keep
 * being rewritten cannot hold their changes back for longer. A file that never contributed content,
 * such as a placeholder, and a file whose empty state is in effect are parsed right away. The reloader
 * calls {@link #load(Duration)} and {@link #accepted(LoadResult)} one call at a time, so this state
 * needs no synchronization.
 */
final class OverrideFileLoader implements FileDataReloader.Loader {
  /**
   * How many retry delays in a row loads can hold files back for.
   */
  static final int MAX_HOLD_RETRY_DELAYS = 5;

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
   * A file whose content is in effect and that loads have found empty: since when it has been so
   * without being written, from the monotonic clock, and its state then. The state is the modification
   * time and size of the file, read through any symbolic link, and is only compared for equality.
   */
  private static final class EmptyFile {
    long sinceNanos;
    FileDataPoller.FileState state;

    EmptyFile(long sinceNanos, FileDataPoller.FileState state) {
      this.sinceNanos = sinceNanos;
      this.state = state;
    }
  }

  private final List<Path> paths;
  private final FileData.DuplicateKeysHandling duplicateKeysHandling;
  private final LongSupplier nanoClock;
  // Whether each configured file contributed content to the data in effect. Updated only by
  // accepted(LoadResult). A file that is not listed did not.
  private final Map<Path, Boolean> appliedHadContent = new HashMap<>();
  // The files whose content is in effect and that loads have found empty since. An entry is removed
  // when a load reads the file with content.
  private final Map<Path, EmptyFile> emptyFiles = new HashMap<>();
  // When the current run of loads that hold files back began, from the monotonic clock; null when the
  // last load did not hold any back.
  private Long holdingSinceNanos = null;

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
   *   in effect and that is found empty is held back until it has stayed empty, without being written,
   *   for this long, and loads stop holding files back once they have held some back for
   *   {@link #MAX_HOLD_RETRY_DELAYS} times this in a row. With zero, every file is parsed as it is.
   * @return the merged result with a summary of each file
   * @throws FileDataException if a file that exists cannot be read or parsed, or the files cannot be
   *   merged, also when another file is held back
   * @throws EmptiedFileException if a file is held back and every other file loads
   */
  @Override
  public LoadResult load(Duration retryDelay) throws FileDataException, EmptiedFileException {
    long now = nanoClock.getAsLong();
    boolean held = false;
    try {
      MessageDigest digest = newDigest();
      Map<DataKind, Map<String, ItemDescriptor>> data = new HashMap<>();
      List<FileSummary> files = new ArrayList<>(paths.size());
      List<Path> heldBack = new ArrayList<>();
      for (Path path : paths) {
        byte[] raw;
        try {
          raw = Files.readAllBytes(path);
        } catch (NoSuchFileException e) {
          // Absence is a valid state, not a failure.
          files.add(new FileSummary(path, false, false, 0, 0));
          continue;
        } catch (IOException e) {
          throw new FileDataException("file " + path + ": unable to read file", e);
        }
        // Content that the parser reads as no document, such as YAML with only a comment, has no
        // entries, and a read can see that much of a file that is being written. Content with only
        // white space is invalid JSON, so it is parsed only when the file is not held back.
        FlagFileRep document = null;
        boolean hasContent = hasContent(raw);
        if (hasContent) {
          document = parse(path, raw);
          hasContent = document != FlagFileRep.NO_DOCUMENT;
        }
        if (hasContent) {
          emptyFiles.remove(path);
        } else if (shouldHoldBack(path, retryDelay, now)) {
          // The other files are still read, so that each emptied file starts its delay in this load,
          // and a failure of another file still fails the load.
          heldBack.add(path);
          continue;
        }
        // One read feeds both the digest and the parse, so the skip-unchanged digest can never disagree
        // with the content that was applied.
        if (digest != null) {
          digest.update(raw);
          digest.update((byte) 0);
        }
        if (document == null) {
          document = parse(path, raw);
        }
        int[] added = addEntries(data, path, document);
        files.add(new FileSummary(path, true, hasContent, added[0], added[1]));
      }

      if (!heldBack.isEmpty()) {
        held = true;
        throw new EmptiedFileException(heldBack, holdBack(heldBack, retryDelay, now));
      }

      ImmutableList.Builder<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> all = ImmutableList.builder();
      for (Map.Entry<DataKind, Map<String, ItemDescriptor>> e : data.entrySet()) {
        all.add(new AbstractMap.SimpleEntry<>(e.getKey(), new KeyedItems<>(ImmutableList.copyOf(e.getValue().entrySet()))));
      }
      Map<String, ItemDescriptor> flags = data.get(FEATURES);
      Map<String, ItemDescriptor> segments = data.get(SEGMENTS);
      return new LoadResult(all.build(), files, flags == null ? 0 : flags.size(),
          segments == null ? 0 : segments.size(), digest == null ? null : digest.digest());
    } finally {
      if (!held) {
        // A run of loads that hold files back ends with any load that does not, whether it succeeds
        // or fails.
        holdingSinceNanos = null;
      }
    }
  }

  private static FlagFileRep parse(Path path, byte[] raw) throws FileDataException {
    try {
      return FlagFileParser.selectForPath(path).parse(new ByteArrayInputStream(raw));
    } catch (FileDataException e) {
      throw new FileDataException("file " + path + ": " + e.getMessage(), e.getCause());
    } catch (IOException e) {
      throw new FileDataException("file " + path + ": cannot read document", e);
    } catch (RuntimeException e) {
      throw new FileDataException("file " + path + ": cannot parse flag or segment data", e);
    }
  }

  // Adds the entries of one file and returns how many flags and segments the merge kept from it.
  private int[] addEntries(Map<DataKind, Map<String, ItemDescriptor>> data, Path path, FlagFileRep document)
      throws FileDataException {
    int flagsAdded = 0;
    int segmentsAdded = 0;
    try {
      if (document.flags != null) {
        for (Map.Entry<String, LDValue> e : document.flags.entrySet()) {
          if (add(data, FEATURES, e.getKey(), FlagFactory.flagFromJson(e.getValue()))) {
            flagsAdded++;
          }
        }
      }
      if (document.flagValues != null) {
        for (Map.Entry<String, LDValue> e : document.flagValues.entrySet()) {
          if (add(data, FEATURES, e.getKey(), FlagFactory.flagWithValue(e.getKey(), e.getValue(), 0))) {
            flagsAdded++;
          }
        }
      }
      if (document.segments != null) {
        for (Map.Entry<String, LDValue> e : document.segments.entrySet()) {
          if (add(data, SEGMENTS, e.getKey(), FlagFactory.segmentFromJson(e.getValue()))) {
            segmentsAdded++;
          }
        }
      }
    } catch (FileDataException e) {
      throw new FileDataException("file " + path + ": " + e.getMessage(), e.getCause());
    } catch (RuntimeException e) {
      // A document can be valid JSON or YAML and still hold a flag or segment that the data model
      // cannot accept. For the override source that is a parse failure of the file, so the last good
      // overrides stay in effect.
      throw new FileDataException("file " + path + ": cannot parse flag or segment data", e);
    }
    return new int[] { flagsAdded, segmentsAdded };
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

  // Decides whether a file that a load found empty is held back. A file whose content is in effect
  // and that is empty now may be in the middle of an in-place rewrite: a writer that truncates the file
  // and then writes the new content. Such a file is held back until it has stayed empty, without being
  // written, for the retry delay. A change of its state means that it was written since a load last
  // found it empty, for example rewritten and truncated again between two loads, so the delay starts
  // over. A file that keeps being written while every load finds it empty, or files that are rewritten
  // in turn so that every load finds one of them empty, would be held back without end, so loads stop
  // holding files back once they have held some back for MAX_HOLD_RETRY_DELAYS retry delays in a row.
  // A file whose content is not in effect, such as a placeholder that has always been empty, or a file
  // whose empty state was applied, is parsed right away. A zero retry delay, which means that the
  // reloader does not retry, holds nothing back: nothing would read the file again. Elapsed times are
  // differences of monotonic clock readings, which do not overflow.
  private boolean shouldHoldBack(Path path, Duration retryDelay, long now) {
    if (!Boolean.TRUE.equals(appliedHadContent.get(path))) {
      return false;
    }
    FileDataPoller.FileState state = FileDataPoller.observe(path);
    EmptyFile empty = emptyFiles.get(path);
    if (empty == null) {
      emptyFiles.put(path, empty = new EmptyFile(now, state));
    } else if (!empty.state.equals(state)) {
      empty.sinceNanos = now;
      empty.state = state;
    }
    long delay = retryDelay.toNanos();
    long holdingFor = holdingSinceNanos == null ? 0 : now - holdingSinceNanos;
    return now - empty.sinceNanos < delay && holdingFor < MAX_HOLD_RETRY_DELAYS * delay;
  }

  // Starts or continues the run of holding loads, and returns how long the reloader waits before it
  // loads again: until the first of the held files has stayed empty for the retry delay, or until the
  // run reaches its limit, whichever comes first, plus a tenth of the retry delay, so that a timer that
  // fires a little early still finds the delay over. A change signal for a write starts a load sooner.
  private long holdBack(List<Path> heldBack, Duration retryDelay, long now) {
    if (holdingSinceNanos == null) {
      holdingSinceNanos = now;
    }
    long delay = retryDelay.toNanos();
    long remaining = MAX_HOLD_RETRY_DELAYS * delay - (now - holdingSinceNanos);
    for (Path path : heldBack) {
      remaining = Math.min(remaining, delay - (now - emptyFiles.get(path).sinceNanos));
    }
    return Math.max(remaining, 0) + delay / 10;
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
