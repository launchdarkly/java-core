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
 * held back until it has stayed empty, without being written, for one retry delay, and for at most
 * five retry delays from the first load that held it back, so that a file that keeps being rewritten
 * cannot hold the changes back for longer. The bound is per file, and it starts over at a load that
 * holds nothing back. A file that never contributed content, such as a placeholder, and a file whose empty state
 * is in effect are parsed right away. The reloader calls {@link #load(Duration)} and
 * {@link #accepted(LoadResult)} one call at a time, so this state needs no synchronization.
 */
final class OverrideFileLoader implements FileDataReloader.Loader {
  /**
   * How many retry delays a file can be held back for, from the first load that held it back.
   */
  static final int MAX_HOLD_RETRY_DELAYS = 5;

  /**
   * Reads a file. Tests substitute one that takes time.
   */
  interface FileReader {
    byte[] read(Path path) throws IOException;
  }

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
   * A file whose content is in effect and that loads have found empty.
   * Times are from the monotonic clock.
   */
  private static final class EmptyFile {
    // Since when the file has been empty without being written, or null after a load read it with
    // content.
    Long sinceNanos;
    // The state of the file then: its modification time and size, read through any symbolic link. It
    // is only compared for equality.
    FileDataPoller.FileState state;
    // When a load first held the file back, or null if no load has since the last load that read the
    // file and held nothing back. Loads that hold another file back keep it, so that a file that keeps
    // being rewritten stays bounded.
    Long firstHeldNanos;
  }

  private final List<Path> paths;
  private final FileData.DuplicateKeysHandling duplicateKeysHandling;
  private final LongSupplier nanoClock;
  // Whether each configured file contributed content to the data in effect. Updated only by
  // accepted(LoadResult). A file that is not listed did not.
  private final Map<Path, Boolean> appliedHadContent = new HashMap<>();
  private final FileReader reader;
  // The files whose content is in effect and that loads have found empty.
  private final Map<Path, EmptyFile> emptyFiles = new HashMap<>();

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
    this(paths, duplicateKeysHandling, nanoClock, Files::readAllBytes);
  }

  /**
   * Creates a loader with a specific monotonic clock and file reader. Visible for tests.
   */
  OverrideFileLoader(List<Path> paths, FileData.DuplicateKeysHandling duplicateKeysHandling,
      LongSupplier nanoClock, FileReader reader) {
    this.paths = new ArrayList<>(paths);
    this.duplicateKeysHandling = duplicateKeysHandling;
    this.nanoClock = nanoClock;
    this.reader = reader;
  }

  /**
   * Reads every configured file in order and merges the entries.
   *
   * @param retryDelay the reloader's retry delay, or zero if it does not retry. A file whose content is
   *   in effect and that is found empty is held back until it has stayed empty, without being written,
   *   for this long, and for at most {@link #MAX_HOLD_RETRY_DELAYS} times this from the first load
   *   that held it back. With zero, every file is parsed as it is.
   * @return the merged result with a summary of each file
   * @throws FileDataException if a file that exists cannot be read or parsed, or the files cannot be
   *   merged, also when another file is held back
   * @throws EmptiedFileException if a file is held back and every other file loads
   */
  @Override
  public LoadResult load(Duration retryDelay) throws FileDataException, EmptiedFileException {
    MessageDigest digest = newDigest();
    Map<DataKind, Map<String, ItemDescriptor>> data = new HashMap<>();
    List<FileSummary> files = new ArrayList<>(paths.size());
    List<Path> heldBack = new ArrayList<>();
    // The files that this load read and did not hold back: read with content, parsed empty, or
    // found missing.
    List<Path> read = new ArrayList<>();
    try {
      for (Path path : paths) {
        byte[] raw;
        try {
          raw = reader.read(path);
        } catch (NoSuchFileException e) {
          // Absence is a valid state, not a failure.
          read.add(path);
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
          EmptyFile empty = emptyFiles.get(path);
          if (empty != null) {
            empty.sinceNanos = null;
          }
        } else if (shouldHoldBack(path, retryDelay, nanoClock.getAsLong())) {
          // The clock is read after this file's own read, so a slow read of another file does not
          // shorten its delay. The other files are still read, so that each emptied file starts its
          // delay in this load, and a failure of another file still fails the load.
          heldBack.add(path);
          continue;
        }
        read.add(path);
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
        throw new EmptiedFileException(heldBack, holdBack(heldBack, retryDelay, nanoClock.getAsLong()));
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
      if (heldBack.isEmpty()) {
        // A load that holds nothing back, whether it is applied, skipped as unchanged, or fails,
        // starts the bound over for every file that it read: such a file is no longer held, so its
        // next rewrite is a new one. A file that the load did not get to keeps its bound.
        for (Path path : read) {
          EmptyFile empty = emptyFiles.get(path);
          if (empty != null) {
            empty.firstHeldNanos = null;
          }
        }
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
  // over. A file that keeps being written while loads find it empty would be held back without end, so
  // each file is held back for at most MAX_HOLD_RETRY_DELAYS retry delays from the first load that held
  // it back. The bound is the file's own, so one file reaching it does not cut short another file's
  // rewrite. It starts over only at a load that holds nothing back, for every file that load read,
  // whatever the load's outcome. Loads that hold another file back keep it, which bounds files
  // rewritten in turn or emptied in staggered turns, and a file that was read during a long failure
  // of another file has a fresh bound when that failure ends. A file whose content is not in effect,
  // such as a placeholder that has always been empty, or a file whose empty state was applied, is
  // parsed right away. A zero retry delay, which means that the reloader does not retry, holds nothing
  // back: nothing would read the file again. Elapsed times are differences of monotonic clock
  // readings, which do not overflow.
  private boolean shouldHoldBack(Path path, Duration retryDelay, long now) {
    if (!Boolean.TRUE.equals(appliedHadContent.get(path))) {
      return false;
    }
    FileDataPoller.FileState state = FileDataPoller.observe(path);
    EmptyFile empty = emptyFiles.computeIfAbsent(path, p -> new EmptyFile());
    if (empty.sinceNanos == null || !state.equals(empty.state)) {
      empty.sinceNanos = now;
      empty.state = state;
    }
    long delay = retryDelay.toNanos();
    if (now - empty.sinceNanos >= delay) {
      // The file has stayed empty for the whole delay, so it is parsed as it is. Its bound starts over
      // only at a load that holds nothing back, so that files that stay empty in staggered turns, each
      // keeping the loads holding while the other settles, cannot hold the changes back without end.
      return false;
    }
    if (empty.firstHeldNanos != null && now - empty.firstHeldNanos >= MAX_HOLD_RETRY_DELAYS * delay) {
      return false;
    }
    if (empty.firstHeldNanos == null) {
      empty.firstHeldNanos = now;
    }
    return true;
  }

  // Returns how long the reloader waits before it loads again: until the first of the held files has
  // stayed empty for the retry delay or reaches its bound, plus a tenth of the retry delay, so that a
  // timer that fires a little early still finds the time over. A change signal for a write starts a
  // load sooner.
  private long holdBack(List<Path> heldBack, Duration retryDelay, long now) {
    long delay = retryDelay.toNanos();
    long remaining = Long.MAX_VALUE;
    for (Path path : heldBack) {
      EmptyFile empty = emptyFiles.get(path);
      long delayLeft = delay - (now - empty.sinceNanos);
      long boundLeft = MAX_HOLD_RETRY_DELAYS * delay - (now - empty.firstHeldNanos);
      remaining = Math.min(remaining, Math.min(delayLeft, boundLeft));
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
