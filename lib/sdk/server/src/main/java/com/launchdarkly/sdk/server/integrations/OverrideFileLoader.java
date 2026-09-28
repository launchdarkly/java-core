package com.launchdarkly.sdk.server.integrations;

import com.google.common.collect.ImmutableList;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.integrations.FileDataSourceParsing.FileDataException;
import com.launchdarkly.sdk.server.integrations.FileDataSourceParsing.FlagFactory;
import com.launchdarkly.sdk.server.integrations.FileDataSourceParsing.FlagFileParser;
import com.launchdarkly.sdk.server.integrations.FileDataSourceParsing.FlagFileRep;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.launchdarkly.sdk.server.DataModel.FEATURES;
import static com.launchdarkly.sdk.server.DataModel.SEGMENTS;

/**
 * Reads the files of the file-based override source and merges them into one snapshot.
 * <p>
 * This loader is separate from the file data source's loader because the override source has
 * different rules: a configured file that does not exist contributes no entries, entries keep the
 * versions that the documents specify, a value-only entry becomes a flag that is off and serves the
 * value, and every failure to read or parse a file is reported as a file data error. The file data
 * source keeps its own behavior.
 */
final class OverrideFileLoader {
  /**
   * Describes one configured file after a load.
   */
  static final class FileSummary {
    private final Path path;
    private final boolean present;
    private final int flags;
    private final int segments;

    FileSummary(Path path, boolean present, int flags, int segments) {
      this.path = path;
      this.present = present;
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

  private final List<Path> paths;
  private final FileData.DuplicateKeysHandling duplicateKeysHandling;

  /**
   * Creates a loader.
   *
   * @param paths the files to read, in precedence order
   * @param duplicateKeysHandling what to do when the same key appears in more than one file
   */
  OverrideFileLoader(List<Path> paths, FileData.DuplicateKeysHandling duplicateKeysHandling) {
    this.paths = new ArrayList<>(paths);
    this.duplicateKeysHandling = duplicateKeysHandling;
  }

  /**
   * Reads every configured file in order and merges the entries.
   *
   * @return the merged result with a summary of each file
   * @throws FileDataException if a file that exists cannot be read or parsed, or the files cannot be merged
   */
  LoadResult load() throws FileDataException {
    MessageDigest digest = newDigest();
    Map<DataKind, Map<String, ItemDescriptor>> data = new HashMap<>();
    List<FileSummary> files = new ArrayList<>(paths.size());
    for (Path path : paths) {
      byte[] raw;
      try {
        raw = Files.readAllBytes(path);
      } catch (NoSuchFileException e) {
        // Absence is a valid state, not a failure.
        files.add(new FileSummary(path, false, 0, 0));
        continue;
      } catch (IOException e) {
        throw new FileDataException("file " + path + ": unable to read file", e);
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
        FlagFileParser parser = FlagFileParser.selectForContent(raw);
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
            if (add(data, FEATURES, e.getKey(), FlagFactory.offFlagWithValue(e.getKey(), e.getValue()))) {
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
      files.add(new FileSummary(path, true, flagsAdded, segmentsAdded));
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
