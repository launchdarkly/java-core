package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.integrations.FileDataReloader.EmptiedFileException;
import com.launchdarkly.sdk.server.integrations.FileDataSourceParsing.FileDataException;
import com.launchdarkly.sdk.server.integrations.OverrideFileLoader.FileSummary;
import com.launchdarkly.sdk.server.integrations.OverrideFileLoader.LoadResult;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;
import com.launchdarkly.sdk.server.subsystems.SerializationException;
import com.launchdarkly.testhelpers.TempDir;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static com.launchdarkly.sdk.server.DataModel.FEATURES;
import static com.launchdarkly.sdk.server.DataModel.SEGMENTS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@SuppressWarnings("javadoc")
public class OverrideFileLoaderTest {
  private static Map<DataKind, Map<String, ItemDescriptor>> toMap(LoadResult result) {
    Map<DataKind, Map<String, ItemDescriptor>> out = new HashMap<>();
    for (Map.Entry<DataKind, KeyedItems<ItemDescriptor>> kind : result.getData()) {
      Map<String, ItemDescriptor> items = new HashMap<>();
      for (Map.Entry<String, ItemDescriptor> item : kind.getValue().getItems()) {
        items.put(item.getKey(), item.getValue());
      }
      out.put(kind.getKey(), items);
    }
    return out;
  }

  private static LDValue json(DataKind kind, ItemDescriptor item) {
    return LDValue.parse(kind.serialize(item));
  }

  private static Path write(TempDir dir, String name, String contents) throws Exception {
    Path p = dir.getPath().resolve(name);
    Files.write(p, contents.getBytes());
    return p;
  }

  private static OverrideFileLoader loader(Path... paths) {
    return new OverrideFileLoader(Arrays.asList(paths), FileData.DuplicateKeysHandling.FAIL);
  }

  @Test
  public void loadsFlagsFlagValuesAndSegmentsKeepingDocumentVersions() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.json",
          "{\"flags\":{\"f1\":{\"key\":\"f1\",\"version\":7,\"on\":true,\"variations\":[true],\"fallthrough\":{\"variation\":0}}},"
          + "\"flagValues\":{\"f2\":\"x\"},"
          + "\"segments\":{\"s1\":{\"key\":\"s1\",\"version\":9}}}");
      LoadResult result = loader(file).load(Duration.ZERO);

      Map<DataKind, Map<String, ItemDescriptor>> data = toMap(result);
      assertEquals(7, data.get(FEATURES).get("f1").getVersion());
      assertEquals(LDValue.of(7), json(FEATURES, data.get(FEATURES).get("f1")).get("version"));
      assertEquals(9, data.get(SEGMENTS).get("s1").getVersion());
      assertEquals(LDValue.of(9), json(SEGMENTS, data.get(SEGMENTS).get("s1")).get("version"));
      assertEquals(2, result.getFlagCount());
      assertEquals(1, result.getSegmentCount());
      assertEquals(1, result.getFiles().size());
      assertTrue(result.getFiles().get(0).isPresent());
      assertEquals(2, result.getFiles().get(0).getFlags());
      assertEquals(1, result.getFiles().get(0).getSegments());
      assertEquals(file, result.getFiles().get(0).getPath());
    }
  }

  @Test
  public void valueOnlyEntryBecomesFallthroughFlagServingTheValue() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.json", "{\"flagValues\":{\"f2\":\"x\"}}");
      LoadResult result = loader(file).load(Duration.ZERO);
      ItemDescriptor f2 = toMap(result).get(FEATURES).get("f2");
      assertEquals(0, f2.getVersion());
      LDValue flag = json(FEATURES, f2);
      assertEquals(LDValue.of("f2"), flag.get("key"));
      assertEquals(LDValue.of(0), flag.get("version"));
      assertEquals(LDValue.of(true), flag.get("on"));
      assertEquals(LDValue.ofNull(), flag.get("offVariation"));
      assertEquals(LDValue.of(0), flag.get("fallthrough").get("variation"));
      assertEquals(LDValue.buildArray().add("x").build(), flag.get("variations"));
    }
  }

  @Test
  public void yamlFileIsParsedAsYaml() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", "flagValues:\n  yaml-flag: \"override-value\"\n");
      LoadResult result = loader(file).load(Duration.ZERO);
      assertEquals(LDValue.buildArray().add("override-value").build(),
          json(FEATURES, toMap(result).get(FEATURES).get("yaml-flag")).get("variations"));
    }
  }

  @Test
  public void missingFileContributesNothing() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path present = write(dir, "present.json", "{\"flagValues\":{\"flag\":\"value\"}}");
      Path missing = dir.getPath().resolve("missing.json");
      Path segments = write(dir, "segments.json", "{\"segments\":{\"s1\":{\"key\":\"s1\"}}}");
      LoadResult result = loader(present, missing, segments).load(Duration.ZERO);

      Map<DataKind, Map<String, ItemDescriptor>> data = toMap(result);
      assertEquals(1, data.get(FEATURES).size());
      assertEquals(1, data.get(SEGMENTS).size());
      List<FileSummary> files = result.getFiles();
      assertEquals(3, files.size());
      assertTrue(files.get(0).isPresent());
      assertEquals(1, files.get(0).getFlags());
      assertFalse(files.get(1).isPresent());
      assertEquals(missing, files.get(1).getPath());
      assertEquals(0, files.get(1).getFlags());
      assertTrue(files.get(2).isPresent());
      assertEquals(1, files.get(2).getSegments());
    }
  }

  @Test
  public void allFilesMissingProducesEmptyResult() throws Exception {
    try (TempDir dir = TempDir.create()) {
      LoadResult result = loader(dir.getPath().resolve("a.json"), dir.getPath().resolve("b.json")).load(Duration.ZERO);
      assertEquals(0, result.getFlagCount());
      assertEquals(0, result.getSegmentCount());
      assertFalse(result.getData().iterator().hasNext());
      assertEquals(2, result.getFiles().size());
    }
  }

  @Test
  public void unreadableFileFailsTheLoad() throws Exception {
    try (TempDir dir = TempDir.create()) {
      // A directory exists but cannot be read as a file.
      Path directory = dir.getPath().resolve("dir.json");
      Files.createDirectory(directory);
      try {
        loader(directory).load(Duration.ZERO);
        fail("expected exception");
      } catch (FileDataException e) {
        assertThat(e.getMessage(), containsString(directory.toString()));
        assertThat(e.getMessage(), containsString("unable to read file"));
        assertThat(e.getCause(), instanceOf(java.io.IOException.class));
      }
    }
  }

  @Test
  public void malformedDocumentFailsTheLoadWithFileAttribution() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path good = write(dir, "good.json", "{\"flagValues\":{\"a\":1}}");
      Path bad = write(dir, "bad.json", "{\"flagValues\"");
      try {
        loader(good, bad).load(Duration.ZERO);
        fail("expected exception");
      } catch (FileDataException e) {
        assertThat(e.getMessage(), containsString(bad.toString()));
        assertThat(e.getMessage(), containsString("cannot parse JSON"));
        assertThat(e.getCause(), instanceOf(com.google.gson.JsonSyntaxException.class));
      }
    }
  }

  @Test
  public void modelRejectedDocumentIsAFileDataError() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "bad-model.json", "{\"flags\":{\"f1\":{\"key\":\"f1\",\"rules\":5}}}");
      try {
        loader(file).load(Duration.ZERO);
        fail("expected exception");
      } catch (FileDataException e) {
        assertThat(e.getMessage(), containsString(file.toString()));
        assertThat(e.getMessage(), containsString("cannot parse flag or segment data"));
        assertThat(e.getCause(), instanceOf(SerializationException.class));
      }
    }
  }

  @Test
  public void duplicateKeysFailByDefaultAcrossFilesAndWithinAFile() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path a = write(dir, "a.json", "{\"flagValues\":{\"flag\":\"a\"}}");
      Path b = write(dir, "b.json", "{\"flagValues\":{\"flag\":\"b\"}}");
      try {
        loader(a, b).load(Duration.ZERO);
        fail("expected exception");
      } catch (FileDataException e) {
        assertThat(e.getMessage(), containsString("in features, key \"flag\" was already defined"));
        assertThat(e.getMessage(), containsString(b.toString()));
        assertNull(e.getCause());
      }

      Path both = write(dir, "both.json", "{\"flags\":{\"x\":{\"key\":\"x\"}},\"flagValues\":{\"x\":true}}");
      try {
        loader(both).load(Duration.ZERO);
        fail("expected exception");
      } catch (FileDataException e) {
        assertThat(e.getMessage(), containsString("key \"x\" was already defined"));
      }

      Path seg1 = write(dir, "seg1.json", "{\"segments\":{\"s\":{\"key\":\"s\"}}}");
      Path seg2 = write(dir, "seg2.json", "{\"segments\":{\"s\":{\"key\":\"s\"}}}");
      try {
        loader(seg1, seg2).load(Duration.ZERO);
        fail("expected exception");
      } catch (FileDataException e) {
        assertThat(e.getMessage(), containsString("in segments, key \"s\" was already defined"));
      }
    }
  }

  @Test
  public void ignoreHandlingKeepsFirstFileAndCountsOnlyKeptEntries() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path first = write(dir, "first.json", "{\"flagValues\":{\"shared\":\"first\"}}");
      Path second = write(dir, "second.json", "{\"flagValues\":{\"shared\":\"second\",\"only-second\":\"x\"}}");
      OverrideFileLoader loader = new OverrideFileLoader(Arrays.asList(first, second), FileData.DuplicateKeysHandling.IGNORE);
      LoadResult result = loader.load(Duration.ZERO);

      Map<String, ItemDescriptor> flags = toMap(result).get(FEATURES);
      assertEquals(LDValue.buildArray().add("first").build(), json(FEATURES, flags.get("shared")).get("variations"));
      assertEquals(2, flags.size());
      assertEquals(1, result.getFiles().get(0).getFlags());
      assertEquals(1, result.getFiles().get(1).getFlags()); // the dropped duplicate is not counted
      assertEquals(2, result.getFlagCount());
    }
  }

  @Test
  public void contentHashReflectsRawContentOfPresentFiles() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.json", "{\"flagValues\":{\"a\":1}}");
      Path missing = dir.getPath().resolve("missing.json");
      OverrideFileLoader loader = loader(file, missing);
      byte[] first = loader.load(Duration.ZERO).getContentHash();
      byte[] second = loader.load(Duration.ZERO).getContentHash();
      assertArrayEquals(first, second);

      Files.write(file, "{\"flagValues\":{\"a\":2}}".getBytes());
      byte[] third = loader.load(Duration.ZERO).getContentHash();
      assertThat(Arrays.equals(first, third), is(false));

      // A file that appears changes the digest.
      Files.write(missing, "{}".getBytes());
      byte[] fourth = loader.load(Duration.ZERO).getContentHash();
      assertThat(Arrays.equals(third, fourth), is(false));
    }
  }

  @Test
  public void emptyDocumentContributesNothing() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "empty.json", "{}");
      LoadResult result = loader(file).load(Duration.ZERO);
      assertEquals(0, result.getFlagCount());
      assertTrue(result.getFiles().get(0).isPresent());
      assertThat(result.getFiles().get(0).getFlags(), equalTo(0));
    }
  }

  @Test
  public void loaderReadsFilesInConfiguredOrder() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path a = write(dir, "a.json", "{\"flagValues\":{\"k\":\"a\"}}");
      Path b = write(dir, "b.json", "{\"flagValues\":{\"k\":\"b\"}}");
      OverrideFileLoader loader = new OverrideFileLoader(Arrays.asList(b, a), FileData.DuplicateKeysHandling.IGNORE);
      assertEquals(LDValue.buildArray().add("b").build(),
          json(FEATURES, toMap(loader.load(Duration.ZERO)).get(FEATURES).get("k")).get("variations"));
      assertEquals(Collections.singletonList(b).get(0), loader.load(Duration.ZERO).getFiles().get(0).getPath());
    }
  }

  private static final String YAML_DOC = "flagValues:\n  flag: \"value\"\n";
  private static final String JSON_DOC = "{\"flagValues\":{\"flag\":\"value\"}}";
  private static final Duration RETRY = Duration.ofSeconds(1);

  /**
   * A monotonic clock that the test moves.
   */
  private static final class TestClock {
    final AtomicLong nanos = new AtomicLong(1_000_000_000L);

    void advance(double retryDelays) {
      nanos.addAndGet((long) (retryDelays * RETRY.toNanos()));
    }
  }

  private static OverrideFileLoader loader(TestClock clock, Path... paths) {
    return new OverrideFileLoader(Arrays.asList(paths), FileData.DuplicateKeysHandling.FAIL, clock.nanos::get);
  }

  // Loads and applies the result, as the reloader does with a result it applies.
  private static LoadResult loadAndAccept(OverrideFileLoader loader) throws Exception {
    LoadResult result = loader.load(RETRY);
    loader.accepted(result);
    return result;
  }

  private static void assertNoContentFailure(OverrideFileLoader loader, Path file) throws Exception {
    try {
      loader.load(RETRY);
      fail("expected exception for " + file);
    } catch (FileDataException e) {
      assertEquals("file " + file + ": the file has no content, which is not a JSON document", e.getMessage());
      assertNull(e.getCause());
    }
  }

  private static void assertDeferred(OverrideFileLoader loader, Path... files) throws Exception {
    try {
      loader.load(RETRY);
      fail("expected the empty files " + Arrays.asList(files) + " to be deferred");
    } catch (EmptiedFileException e) {
      assertEquals(Arrays.asList(files), e.getPaths());
    }
  }

  // Truncates the file again with a later modification time than the previous write. File system
  // timestamps can be coarse, so it waits until the time differs.
  private static void truncateAgain(Path file) throws Exception {
    long before = Files.getLastModifiedTime(file).toMillis();
    long deadline = System.currentTimeMillis() + 5000;
    do {
      Thread.sleep(20);
      InPlaceWrites.rewrite(file, "x", () -> { });
      InPlaceWrites.truncate(file);
    } while (Files.getLastModifiedTime(file).toMillis() == before && System.currentTimeMillis() < deadline);
  }

  @Test
  public void formatIsChosenByTheFileNameExtension() throws Exception {
    try (TempDir dir = TempDir.create()) {
      for (String name : new String[] { "a.yaml", "a.yml", "a.YAML", "a.Yml" }) {
        assertEquals(name, 1, loader(write(dir, name, YAML_DOC)).load(Duration.ZERO).getFlagCount());
      }
      // JSON is valid YAML, so a YAML file can hold a JSON document.
      assertEquals(1, loader(write(dir, "json.yaml", JSON_DOC)).load(Duration.ZERO).getFlagCount());
      // Any other file is JSON, whatever its content.
      for (String name : new String[] { "b.json", "b.txt", "b", "b.yaml.bak" }) {
        Path file = write(dir, name, YAML_DOC);
        try {
          loader(file).load(Duration.ZERO);
          fail("expected exception for " + name);
        } catch (FileDataException e) {
          assertThat(e.getMessage(), containsString(file + ": cannot parse JSON"));
        }
      }
    }
  }

  @Test
  public void emptyOrWhiteSpaceJsonFileFailsToParse() throws Exception {
    try (TempDir dir = TempDir.create()) {
      // A file with no extension or another extension is JSON, so it gets the same rule.
      for (String name : new String[] { "empty.json", "empty", "empty.txt" }) {
        for (String contents : new String[] { "", "  \n\t\r\n" }) {
          Path file = write(dir, name, contents);
          assertNoContentFailure(loader(file), file);
        }
      }
    }
  }

  @Test
  public void emptyOrWhiteSpaceYamlFileHasNoEntries() throws Exception {
    try (TempDir dir = TempDir.create()) {
      for (String name : new String[] { "empty.yaml", "empty.yml" }) {
        for (String contents : new String[] { "", "  \n\n" }) {
          LoadResult result = loader(write(dir, name, contents)).load(RETRY);
          assertEquals(0, result.getFlagCount());
          assertTrue(result.getFiles().get(0).isPresent());
          assertFalse(result.getFiles().get(0).hasContent());
        }
      }
    }
  }

  @Test
  public void emptiedYamlFileIsDeferredForOneRetryDelayAndThenHasNoEntries() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", YAML_DOC);
      TestClock clock = new TestClock();
      OverrideFileLoader loader = loader(clock, file);
      assertTrue(loadAndAccept(loader).getFiles().get(0).hasContent());

      InPlaceWrites.truncate(file);
      assertDeferred(loader, file);
      clock.advance(0.85);
      assertDeferred(loader, file);
      // The file has stayed empty without being written for nine tenths of a retry delay, which is
      // when the retry armed by the first deferral can fire if its timer is slightly early.
      clock.advance(0.05);
      assertEquals(0, loader.load(RETRY).getFlagCount());
      // It is not deferred again until it changes.
      assertEquals(0, loader.load(RETRY).getFlagCount());
    }
  }

  @Test
  public void emptiedJsonFileIsDeferredForOneRetryDelayAndThenFailsToParse() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.json", JSON_DOC);
      TestClock clock = new TestClock();
      OverrideFileLoader loader = loader(clock, file);
      loadAndAccept(loader);

      InPlaceWrites.truncate(file);
      assertDeferred(loader, file);
      clock.advance(1);
      assertNoContentFailure(loader, file);
      assertNoContentFailure(loader, file);
    }
  }

  @Test
  public void contentThatIsOnlyWhiteSpaceOrAByteOrderMarkCountsAsEmpty() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", YAML_DOC);
      OverrideFileLoader loader = loader(new TestClock(), file);
      for (String contents : new String[] { " \n\t\r\n", "\uFEFF", "\uFEFF\n" }) {
        assertEquals(1, loadAndAccept(loader).getFlagCount());
        InPlaceWrites.rewrite(file, contents, () -> { });
        assertDeferred(loader, file);
        InPlaceWrites.rewrite(file, YAML_DOC, () -> { });
      }
    }
  }

  @Test
  public void contentThatWasReadButNotAppliedDoesNotDeferTheFile() throws Exception {
    // Only the data in effect counts. A load whose result was not applied, for example because
    // another file failed to parse, does not make the file's later emptiness a rewrite.
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", YAML_DOC);
      OverrideFileLoader loader = loader(new TestClock(), file);
      loader.load(RETRY);

      InPlaceWrites.truncate(file);
      assertEquals(0, loader.load(RETRY).getFlagCount());
    }
  }

  @Test
  public void fileThatIsEmptyFromTheStartIsNeverDeferred() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path placeholder = write(dir, "placeholder.yaml", "");
      Path data = write(dir, "data.json", JSON_DOC);
      OverrideFileLoader loader = loader(new TestClock(), placeholder, data);
      for (int i = 0; i < 3; i++) {
        assertEquals(1, loadAndAccept(loader).getFlagCount());
      }
      truncateAgain(placeholder);
      assertEquals(1, loadAndAccept(loader).getFlagCount());
    }
  }

  @Test
  public void fileWhoseEmptyStateIsInEffectIsNotDeferredAgain() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", YAML_DOC);
      TestClock clock = new TestClock();
      OverrideFileLoader loader = loader(clock, file);
      loadAndAccept(loader);

      InPlaceWrites.truncate(file);
      assertDeferred(loader, file);
      clock.advance(1);
      assertEquals(0, loadAndAccept(loader).getFlagCount());

      // A new truncation of the file whose empty state is in effect is parsed right away.
      truncateAgain(file);
      assertEquals(0, loader.load(RETRY).getFlagCount());
    }
  }

  @Test
  public void fileThatIsDeletedAndCreatedEmptyIsParsed() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", YAML_DOC);
      OverrideFileLoader loader = loader(new TestClock(), file);
      loadAndAccept(loader);

      Files.delete(file);
      assertFalse(loadAndAccept(loader).getFiles().get(0).isPresent());
      Files.write(file, new byte[0]);
      LoadResult result = loader.load(RETRY);
      assertTrue(result.getFiles().get(0).isPresent());
      assertEquals(0, result.getFlagCount());
    }
  }

  @Test
  public void newTruncationStartsTheGraceAgain() throws Exception {
    // A write and a new truncation that no load saw in between: the file is a new rewrite, not one
    // that has stayed empty.
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", YAML_DOC);
      TestClock clock = new TestClock();
      OverrideFileLoader loader = loader(clock, file);
      loadAndAccept(loader);

      InPlaceWrites.truncate(file);
      assertDeferred(loader, file);
      clock.advance(0.8);
      truncateAgain(file);
      assertDeferred(loader, file);
      clock.advance(0.8);
      assertDeferred(loader, file);
      clock.advance(0.1);
      assertEquals(0, loader.load(RETRY).getFlagCount());
    }
  }

  @Test
  public void fileThatKeepsBeingEmptiedIsParsedAfterTheCap() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", YAML_DOC);
      TestClock clock = new TestClock();
      OverrideFileLoader loader = loader(clock, file);
      loadAndAccept(loader);

      InPlaceWrites.truncate(file);
      assertDeferred(loader, file);
      // Every load finds a new truncation, so the grace keeps starting again, up to the cap of five
      // retry delays from when the file was first found empty.
      for (int i = 0; i < 5; i++) {
        clock.advance(0.9);
        truncateAgain(file);
        assertDeferred(loader, file);
      }
      clock.advance(0.5);
      truncateAgain(file);
      assertEquals(0, loader.load(RETRY).getFlagCount());
    }
  }

  @Test
  public void contentReadEndsTheEpisode() throws Exception {
    // The cap counts from when the file was first found empty in the current episode.
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", YAML_DOC);
      TestClock clock = new TestClock();
      OverrideFileLoader loader = loader(clock, file);
      loadAndAccept(loader);

      InPlaceWrites.truncate(file);
      assertDeferred(loader, file);
      clock.advance(4.5);
      InPlaceWrites.rewrite(file, YAML_DOC, () -> { });
      assertEquals(1, loader.load(RETRY).getFlagCount());

      truncateAgain(file);
      clock.advance(0.1);
      assertDeferred(loader, file);
      clock.advance(0.5);
      assertDeferred(loader, file);
    }
  }

  @Test
  public void missingFileEndsTheEpisode() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", YAML_DOC);
      TestClock clock = new TestClock();
      OverrideFileLoader loader = loader(clock, file);
      loadAndAccept(loader);

      InPlaceWrites.truncate(file);
      assertDeferred(loader, file);
      clock.advance(4.5);
      Files.delete(file);
      assertFalse(loader.load(RETRY).getFiles().get(0).isPresent());

      Files.write(file, new byte[0]);
      clock.advance(0.6);
      assertDeferred(loader, file);
    }
  }

  @Test
  public void withoutRetriesAnEmptiedFileIsParsedRightAway() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", YAML_DOC);
      OverrideFileLoader loader = loader(new TestClock(), file);
      loader.accepted(loader.load(Duration.ZERO));

      InPlaceWrites.truncate(file);
      assertEquals(0, loader.load(Duration.ZERO).getFlagCount());
    }
  }

  @Test
  public void filesEmptiedTogetherAreDeferredFromTheSameLoad() throws Exception {
    // A load that defers one file still reads the others, so each emptied file starts its delay in
    // that load and all of them are parsed after one retry delay, not one retry delay per file.
    try (TempDir dir = TempDir.create()) {
      Path first = write(dir, "first.yaml", YAML_DOC);
      Path second = write(dir, "second.yaml", "flagValues:\n  other: \"value\"\n");
      TestClock clock = new TestClock();
      OverrideFileLoader loader = loader(clock, first, second);
      assertEquals(2, loadAndAccept(loader).getFlagCount());

      InPlaceWrites.truncate(first);
      InPlaceWrites.truncate(second);
      assertDeferred(loader, first, second);
      clock.advance(0.9);
      assertEquals(0, loader.load(RETRY).getFlagCount());
    }
  }

  @Test
  public void failureOfAnotherFileFailsALoadThatDefersAFile() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path first = write(dir, "first.yaml", YAML_DOC);
      Path second = write(dir, "second.json", JSON_DOC.replace("flag", "other"));
      OverrideFileLoader loader = loader(new TestClock(), first, second);
      loadAndAccept(loader);

      InPlaceWrites.truncate(first);
      InPlaceWrites.rewrite(second, "{\"flagValues\"", () -> { });
      try {
        loader.load(RETRY);
        fail("expected exception");
      } catch (FileDataException e) {
        assertThat(e.getMessage(), containsString(second + ": cannot parse JSON"));
      }
    }
  }
}
