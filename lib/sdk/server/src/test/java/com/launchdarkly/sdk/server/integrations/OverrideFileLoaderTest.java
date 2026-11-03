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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import static org.hamcrest.Matchers.not;
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
   * A monotonic clock that the test moves. It starts near the largest value, so that elapsed times
   * cross the overflow of the clock's readings.
   */
  private static final class TestClock {
    final AtomicLong nanos = new AtomicLong(Long.MAX_VALUE - RETRY.toNanos() / 2);

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

  // Asserts that a load holds back the given files and returns how long it asks the reloader to
  // wait, in retry delays.
  private static double assertHeld(OverrideFileLoader loader, Path... files) throws Exception {
    try {
      loader.load(RETRY);
      fail("expected the empty files " + Arrays.asList(files) + " to be held back");
      return 0;
    } catch (EmptiedFileException e) {
      assertEquals(Arrays.asList(files), e.getPaths());
      return (double) e.getRetryAfterNanos() / RETRY.toNanos();
    }
  }

  // Writes the file again, in place, with a later modification time than the previous write. File
  // system timestamps can be coarse, so it waits until the time differs. It writes other content first,
  // so that the file is modified even when the new content is what it already holds.
  private static void writeAgain(Path file, String contents) throws Exception {
    long before = Files.getLastModifiedTime(file).toMillis();
    long deadline = System.currentTimeMillis() + 5000;
    do {
      Thread.sleep(20);
      InPlaceWrites.rewrite(file, "x", () -> { });
      InPlaceWrites.rewrite(file, contents, () -> { });
    } while (Files.getLastModifiedTime(file).toMillis() == before && System.currentTimeMillis() < deadline);
    assertThat(Files.getLastModifiedTime(file).toMillis(), not(equalTo(before)));
  }

  private static void truncateAgain(Path file) throws Exception {
    writeAgain(file, "");
  }

  @Test
  public void formatIsChosenByTheFileNameExtension() throws Exception {
    try (TempDir dir = TempDir.create()) {
      for (String name : new String[] { "a.yaml", "a.yml", "a.YAML", "a.Yml" }) {
        assertEquals(name, 1, loader(write(dir, name, YAML_DOC)).load(Duration.ZERO).getFlagCount());
      }
      // A YAML file can hold most JSON documents. The YAML parser reads YAML 1.1, which rejects some
      // JSON, such as indentation with tabs or the escape \/.
      assertEquals(1, loader(write(dir, "json.yaml", JSON_DOC)).load(Duration.ZERO).getFlagCount());
      for (String json : new String[] { "{\n\t\"flagValues\": {\"flag\": \"v\"}\n}", "{\"flagValues\":{\"flag\":\"a\\/b\"}}" }) {
        Path file = write(dir, "strict.yaml", json);
        try {
          loader(file).load(Duration.ZERO);
          fail("expected the YAML parser to reject " + json);
        } catch (FileDataException e) {
          assertThat(e.getMessage(), containsString(file + ": unable to parse YAML"));
        }
      }
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
  public void yamlFileWithNoDocumentHasNoEntries() throws Exception {
    try (TempDir dir = TempDir.create()) {
      for (String name : new String[] { "empty.yaml", "empty.yml" }) {
        for (String contents : new String[] { "", "  \n\n", "# only a comment\n", "---\n" }) {
          LoadResult result = loader(write(dir, name, contents)).load(RETRY);
          assertEquals(0, result.getFlagCount());
          assertTrue(result.getFiles().get(0).isPresent());
          assertFalse(contents, result.getFiles().get(0).hasContent());
        }
      }
    }
  }

  @Test
  public void documentWithNoEntriesHasContent() throws Exception {
    try (TempDir dir = TempDir.create()) {
      for (String name : new String[] { "empty.yaml", "empty.json" }) {
        LoadResult result = loader(write(dir, name, "{}")).load(RETRY);
        assertEquals(0, result.getFlagCount());
        assertTrue(name, result.getFiles().get(0).hasContent());
      }
    }
  }

  @Test
  public void emptiedYamlFileIsHeldForExactlyTheRetryDelayAndThenHasNoEntries() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", YAML_DOC);
      TestClock clock = new TestClock();
      OverrideFileLoader loader = loader(clock, file);
      assertTrue(loadAndAccept(loader).getFiles().get(0).hasContent());

      InPlaceWrites.truncate(file);
      assertHeld(loader, file);
      clock.advance(0.95);
      assertHeld(loader, file);
      // The file has stayed empty without being written for the retry delay.
      clock.advance(0.05);
      assertEquals(0, loader.load(RETRY).getFlagCount());
      // It is not held back again until it changes.
      assertEquals(0, loader.load(RETRY).getFlagCount());
    }
  }

  @Test
  public void emptiedJsonFileIsHeldForTheRetryDelayAndThenFailsToParse() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.json", JSON_DOC);
      TestClock clock = new TestClock();
      OverrideFileLoader loader = loader(clock, file);
      loadAndAccept(loader);

      InPlaceWrites.truncate(file);
      assertHeld(loader, file);
      clock.advance(1);
      assertNoContentFailure(loader, file);
      assertNoContentFailure(loader, file);
    }
  }

  @Test
  public void holdAsksForARetryWhenTheFirstDelayEnds() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path first = write(dir, "first.yaml", YAML_DOC);
      Path second = write(dir, "second.yaml", "flagValues:\n  other: \"value\"\n");
      TestClock clock = new TestClock();
      OverrideFileLoader loader = loader(clock, first, second);
      loadAndAccept(loader);

      // The retry comes when the delay ends, plus a tenth of the retry delay for a timer that fires a
      // little early.
      InPlaceWrites.truncate(first);
      assertEquals(1.1, assertHeld(loader, first), 1e-9);
      clock.advance(0.4);
      assertEquals(0.7, assertHeld(loader, first), 1e-9);
      // A second file found empty later does not delay the retry for the first.
      InPlaceWrites.truncate(second);
      clock.advance(0.2);
      assertEquals(0.5, assertHeld(loader, first, second), 1e-9);
    }
  }

  @Test
  public void holdAsksForARetryWhenTheBoundEnds() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", YAML_DOC);
      TestClock clock = new TestClock();
      OverrideFileLoader loader = loader(clock, file);
      loadAndAccept(loader);

      InPlaceWrites.truncate(file);
      assertHeld(loader, file);
      for (int i = 0; i < 4; i++) {
        clock.advance(0.9);
        writeAgain(file, " ");
        assertHeld(loader, file);
      }
      // 4.6 retry delays after the first hold, the file is written again. Its delay would end at 5.6,
      // its bound at 5.0.
      clock.advance(1.0);
      writeAgain(file, "  ");
      assertEquals(0.5, assertHeld(loader, file), 1e-9);
    }
  }

  @Test
  public void contentThatIsOnlyWhiteSpaceOrAByteOrderMarkCountsAsEmpty() throws Exception {
    // A read can see only the byte order mark that an editor writes first. For JSON that is not a
    // parse failure to report but an empty file to hold back.
    for (String name : new String[] { "overrides.yaml", "overrides.json" }) {
      try (TempDir dir = TempDir.create()) {
        String doc = name.endsWith(".json") ? JSON_DOC : YAML_DOC;
        Path file = write(dir, name, doc);
        OverrideFileLoader loader = loader(new TestClock(), file);
        for (String contents : new String[] { " \n\t\r\n", "\uFEFF", "\uFEFF\n" }) {
          assertEquals(name, 1, loadAndAccept(loader).getFlagCount());
          InPlaceWrites.rewrite(file, contents, () -> { });
          assertHeld(loader, file);
          InPlaceWrites.rewrite(file, doc, () -> { });
        }
      }
    }
  }

  @Test
  public void fileEmptiedAgainWithTheSameStateAfterContentStartsANewDelay() throws Exception {
    // File system timestamps are coarse, so a file can be emptied again with the modification time
    // and size that it had when it was last found empty. A load that read content in between shows
    // that this is a new emptying, so its delay starts over. Nothing is applied in between: the test
    // does not accept the result of the load that reads the content.
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", YAML_DOC);
      TestClock clock = new TestClock();
      OverrideFileLoader loader = loader(clock, file);
      loadAndAccept(loader);

      InPlaceWrites.truncate(file);
      java.nio.file.attribute.FileTime emptiedAt = Files.getLastModifiedTime(file);
      assertHeld(loader, file);
      clock.advance(0.8);
      InPlaceWrites.rewrite(file, YAML_DOC, () -> { });
      assertEquals(1, loader.load(RETRY).getFlagCount());

      clock.advance(0.4);
      InPlaceWrites.truncate(file);
      Files.setLastModifiedTime(file, emptiedAt);
      assertHeld(loader, file);
      // The new delay is still running a little later.
      clock.advance(0.2);
      assertHeld(loader, file);
    }
  }

  @Test
  public void yamlWithOnlyACommentOrADocumentMarkerIsHeld() throws Exception {
    // A read can see only the first line of a YAML file that is being written.
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", YAML_DOC);
      OverrideFileLoader loader = loader(new TestClock(), file);
      for (String contents : new String[] { "# overrides for the incident\n", "---\n" }) {
        assertEquals(1, loadAndAccept(loader).getFlagCount());
        InPlaceWrites.rewrite(file, contents, () -> { });
        assertHeld(loader, file);
        InPlaceWrites.rewrite(file, YAML_DOC, () -> { });
      }
    }
  }

  @Test
  public void documentWithNoEntriesIsAppliedRightAway() throws Exception {
    // {} is how to remove a file's overrides without deleting it.
    try (TempDir dir = TempDir.create()) {
      for (String name : new String[] { "overrides.yaml", "overrides.json" }) {
        Path file = write(dir, name, name.endsWith(".json") ? JSON_DOC : YAML_DOC);
        OverrideFileLoader loader = loader(new TestClock(), file);
        loadAndAccept(loader);

        InPlaceWrites.rewrite(file, "{}", () -> { });
        assertEquals(name, 0, loader.load(RETRY).getFlagCount());
      }
    }
  }

  @Test
  public void contentThatWasReadButNotAppliedDoesNotHoldTheFile() throws Exception {
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
  public void fileThatHasNoContentFromTheStartIsNeverHeld() throws Exception {
    for (String placeholderContents : new String[] { "", "# placeholder\n" }) {
      try (TempDir dir = TempDir.create()) {
        Path placeholder = write(dir, "placeholder.yaml", placeholderContents);
        Path data = write(dir, "data.json", JSON_DOC);
        OverrideFileLoader loader = loader(new TestClock(), placeholder, data);
        for (int i = 0; i < 3; i++) {
          assertEquals(1, loadAndAccept(loader).getFlagCount());
        }
        truncateAgain(placeholder);
        assertEquals(1, loadAndAccept(loader).getFlagCount());
      }
    }
  }

  @Test
  public void fileWhoseEmptyStateIsInEffectIsNotHeldAgain() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", YAML_DOC);
      TestClock clock = new TestClock();
      OverrideFileLoader loader = loader(clock, file);
      loadAndAccept(loader);

      InPlaceWrites.truncate(file);
      assertHeld(loader, file);
      clock.advance(1);
      assertEquals(0, loadAndAccept(loader).getFlagCount());

      // A new truncation of the file whose empty state is in effect is parsed right away.
      writeAgain(file, " ");
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
  public void newWriteStartsTheDelayAgain() throws Exception {
    // A write and a new truncation that no load saw in between: the file is in a new rewrite, not one
    // that has stayed empty.
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", YAML_DOC);
      TestClock clock = new TestClock();
      OverrideFileLoader loader = loader(clock, file);
      loadAndAccept(loader);

      InPlaceWrites.truncate(file);
      assertHeld(loader, file);
      clock.advance(0.8);
      writeAgain(file, YAML_DOC);
      InPlaceWrites.truncate(file);
      assertHeld(loader, file);
      clock.advance(0.95);
      assertHeld(loader, file);
      clock.advance(0.05);
      assertEquals(0, loader.load(RETRY).getFlagCount());
    }
  }

  @Test
  public void newWriteOfALinkedFileStartsTheDelayAgain() throws Exception {
    // The state of a file that is configured through a symbolic link is that of the file it points
    // to; the link itself does not change when the file is written.
    try (TempDir dir = TempDir.create()) {
      Path target = write(dir, "target.yaml", YAML_DOC);
      Path link = dir.getPath().resolve("overrides.yaml");
      try {
        Files.createSymbolicLink(link, target);
      } catch (UnsupportedOperationException | java.io.IOException e) {
        org.junit.Assume.assumeNoException("symbolic links are not available", e);
      }
      TestClock clock = new TestClock();
      OverrideFileLoader loader = loader(clock, link);
      loadAndAccept(loader);

      InPlaceWrites.truncate(target);
      assertHeld(loader, link);
      clock.advance(0.8);
      truncateAgain(target);
      assertHeld(loader, link);
      clock.advance(0.95);
      assertHeld(loader, link);
      clock.advance(0.05);
      assertEquals(0, loader.load(RETRY).getFlagCount());
    }
  }

  @Test
  public void fileThatKeepsBeingWrittenIsParsedAfterItsBound() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", YAML_DOC);
      TestClock clock = new TestClock();
      OverrideFileLoader loader = loader(clock, file);
      loadAndAccept(loader);

      InPlaceWrites.truncate(file);
      assertHeld(loader, file);
      // Every load finds a new write, so the delay keeps starting again, up to the bound of five retry
      // delays from the first hold.
      for (int i = 0; i < 5; i++) {
        clock.advance(0.9);
        truncateAgain(file);
        assertHeld(loader, file);
      }
      clock.advance(0.5);
      truncateAgain(file);
      assertEquals(0, loader.load(RETRY).getFlagCount());
    }
  }

  @Test
  public void filesRewrittenInTurnAreParsedAfterTheirBound() throws Exception {
    // Each load finds one of the files empty and the other with content, so each file's own delay
    // starts again whenever it is found empty. A read with content keeps the file's bound, so it
    // still ends.
    try (TempDir dir = TempDir.create()) {
      Path a = write(dir, "a.yaml", YAML_DOC);
      Path b = write(dir, "b.yaml", "flagValues:\n  other: \"value\"\n");
      TestClock clock = new TestClock();
      OverrideFileLoader loader = loader(clock, a, b);
      loadAndAccept(loader);

      InPlaceWrites.truncate(a);
      assertHeld(loader, a);
      for (int i = 0; i < 9; i++) {
        clock.advance(0.5);
        Path emptied = i % 2 == 0 ? b : a;
        Path written = i % 2 == 0 ? a : b;
        writeAgain(written, written == a ? YAML_DOC : "flagValues:\n  other: \"value\"\n");
        truncateAgain(emptied);
        assertHeld(loader, emptied);
      }
      clock.advance(0.5);
      writeAgain(b, "flagValues:\n  other: \"value\"\n");
      truncateAgain(a);
      assertEquals(1, loader.load(RETRY).getFlagCount());
    }
  }

  @Test
  public void loadThatHoldsNothingBackStartsTheBoundOver() throws Exception {
    // A load that reads the file with content and holds nothing back starts the file's bound over,
    // although nothing is applied here because the test does not accept the result.
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", YAML_DOC);
      TestClock clock = new TestClock();
      OverrideFileLoader loader = loader(clock, file);
      loadAndAccept(loader);

      InPlaceWrites.truncate(file);
      assertHeld(loader, file);
      for (int i = 0; i < 5; i++) {
        clock.advance(0.9);
        truncateAgain(file);
        assertHeld(loader, file);
      }
      writeAgain(file, YAML_DOC);
      assertEquals(1, loader.load(RETRY).getFlagCount());
      truncateAgain(file);
      clock.advance(0.5);
      assertHeld(loader, file);
      clock.advance(0.4);
      assertHeld(loader, file);
    }
  }

  @Test
  public void applyingDataStartsTheBoundOver() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", YAML_DOC);
      TestClock clock = new TestClock();
      OverrideFileLoader loader = loader(clock, file);
      loadAndAccept(loader);

      InPlaceWrites.truncate(file);
      assertHeld(loader, file);
      for (int i = 0; i < 5; i++) {
        clock.advance(0.9);
        truncateAgain(file);
        assertHeld(loader, file);
      }
      writeAgain(file, YAML_DOC);
      assertEquals(1, loadAndAccept(loader).getFlagCount());
      truncateAgain(file);
      clock.advance(0.5);
      assertHeld(loader, file);
      clock.advance(0.4);
      assertHeld(loader, file);
    }
  }

  @Test
  public void jsonFileThatStaysEmptyIsHeldBackAgainWhenRewrittenLater() throws Exception {
    // A JSON file that stays empty is parsed after the delay and fails. That load holds nothing back,
    // so the file's bound starts over, and when it is later rewritten in place, the new truncation is
    // held back again instead of reported.
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.json", JSON_DOC);
      TestClock clock = new TestClock();
      OverrideFileLoader loader = loader(clock, file);
      loadAndAccept(loader);

      InPlaceWrites.truncate(file);
      assertHeld(loader, file);
      clock.advance(1);
      assertNoContentFailure(loader, file);
      clock.advance(10);
      assertNoContentFailure(loader, file);

      writeAgain(file, " ");
      assertHeld(loader, file);
      InPlaceWrites.rewrite(file, JSON_DOC, () -> { });
      assertEquals(1, loader.load(RETRY).getFlagCount());
    }
  }

  @Test
  public void failingOtherFileDoesNotRestartTheBound() throws Exception {
    // a.yaml keeps being written while empty. Another file fails to load now and then, which must not
    // give a.yaml a fresh bound.
    try (TempDir dir = TempDir.create()) {
      Path a = write(dir, "a.yaml", YAML_DOC);
      String other = "{\"flagValues\":{\"other\":\"value\"}}";
      Path c = write(dir, "c.json", other);
      TestClock clock = new TestClock();
      OverrideFileLoader loader = loader(clock, a, c);
      loadAndAccept(loader);

      InPlaceWrites.truncate(a);
      assertHeld(loader, a);
      for (int i = 1; i < 10; i++) {
        clock.advance(0.5);
        writeAgain(a, " ");
        if (i % 2 == 1) {
          InPlaceWrites.rewrite(c, "{bad", () -> { });
          try {
            loader.load(RETRY);
            fail("expected exception");
          } catch (FileDataException e) {
            assertThat(e.getMessage(), containsString(c.toString()));
          }
        } else {
          InPlaceWrites.rewrite(c, other, () -> { });
          assertHeld(loader, a);
        }
      }
      clock.advance(0.5);
      writeAgain(a, "  ");
      InPlaceWrites.rewrite(c, other, () -> { });
      LoadResult result = loader.load(RETRY);
      assertEquals(1, result.getFlagCount());
      assertFalse(result.getFiles().get(0).hasContent());
    }
  }

  // Loads the files and applies the result, as the reloader does, or reports that the load held a
  // file back.
  private static boolean loadApplies(OverrideFileLoader loader) throws Exception {
    try {
      loader.accepted(loader.load(RETRY));
      return true;
    } catch (EmptiedFileException e) {
      return false;
    }
  }

  @Test
  public void filesEmptiedInStaggeredTurnsAreAppliedAfterTheBound() throws Exception {
    // Two files stay empty and are truncated again in turn, so that whenever one of them has stayed
    // empty for the whole delay, the other is still within its delay and holds the load back. A file
    // that settles so does not get a fresh bound while the other is held back.
    try (TempDir dir = TempDir.create()) {
      Path a = write(dir, "a.yaml", YAML_DOC);
      Path b = write(dir, "b.yaml", "flagValues:\n  other: \"value\"\n");
      TestClock clock = new TestClock();
      OverrideFileLoader loader = loader(clock, a, b);
      loadAndAccept(loader);

      InPlaceWrites.truncate(a);
      assertHeld(loader, a);
      boolean applied = false;
      for (int i = 1; i <= 14 && !applied; i++) {
        clock.advance(0.9);
        if (i == 1) {
          InPlaceWrites.truncate(b);
        } else {
          truncateAgain(i % 2 == 1 ? b : a);
        }
        applied = loadApplies(loader);
        if (!applied) {
          // The retry that the hold arms, a little after the other file's delay is over.
          clock.advance(0.2);
          applied = loadApplies(loader);
          clock.advance(-0.2);
        }
      }
      assertTrue("the files were held back without end", applied);
    }
  }

  @Test
  public void boundOfAFileHeldBackDuringAFailureStartsOverOnceItIsReadAgain() throws Exception {
    // While b.json fails to load, a.yaml is rewritten once and its gap is held back. Later, b.json is
    // fixed while a.yaml is in the middle of another rewrite, which is held back with a bound of its own.
    try (TempDir dir = TempDir.create()) {
      Path a = write(dir, "a.yaml", YAML_DOC);
      String other = "{\"flagValues\":{\"other\":\"value\"}}";
      Path b = write(dir, "b.json", other);
      TestClock clock = new TestClock();
      OverrideFileLoader loader = loader(clock, a, b);
      loadAndAccept(loader);

      InPlaceWrites.rewrite(b, "{bad", () -> { });
      InPlaceWrites.truncate(a);
      assertLoadFailsFor(loader, b);
      InPlaceWrites.rewrite(a, "flagValues:\n  flag: \"other value\"\n", () -> { });
      assertLoadFailsFor(loader, b);
      clock.advance(6);
      assertLoadFailsFor(loader, b);

      InPlaceWrites.rewrite(b, other, () -> { });
      InPlaceWrites.truncate(a);
      assertHeld(loader, a);
    }
  }

  @Test
  public void fileFoundMissingStartsANewDelayWhenItIsCreatedEmptyAgain() throws Exception {
    // While b.json fails, a.yaml is emptied, deleted, and created empty again with the state it had
    // when it was first found empty, as within one tick of a coarse clock. Being missing ended its
    // empty state, so it is held back with a new delay.
    try (TempDir dir = TempDir.create()) {
      Path a = write(dir, "a.yaml", YAML_DOC);
      String other = "{\"flagValues\":{\"other\":\"value\"}}";
      Path b = write(dir, "b.json", other);
      TestClock clock = new TestClock();
      OverrideFileLoader loader = loader(clock, a, b);
      loadAndAccept(loader);

      InPlaceWrites.rewrite(b, "{bad", () -> { });
      InPlaceWrites.truncate(a);
      java.nio.file.attribute.FileTime emptiedAt = Files.getLastModifiedTime(a);
      assertLoadFailsFor(loader, b);
      clock.advance(0.5);
      Files.delete(a);
      assertLoadFailsFor(loader, b);
      clock.advance(0.67);
      Files.write(a, new byte[0]);
      Files.setLastModifiedTime(a, emptiedAt);
      assertLoadFailsFor(loader, b);

      InPlaceWrites.rewrite(b, other, () -> { });
      assertHeld(loader, a);
    }
  }

  @Test
  public void fixOfAFileSavedInPlaceWithAnErrorIsHeldBack() throws Exception {
    // An operator saves the file in place with a mistake, and saves the fix in place long after. The
    // loads in between fail on the file, and the fix's own gap is held back.
    for (String name : new String[] { "data.yaml", "data.json" }) {
      try (TempDir dir = TempDir.create()) {
        boolean yaml = name.endsWith(".yaml");
        Path file = write(dir, name, yaml ? YAML_DOC : JSON_DOC);
        TestClock clock = new TestClock();
        OverrideFileLoader loader = loader(clock, file);
        loadAndAccept(loader);

        InPlaceWrites.truncate(file);
        assertHeld(loader, file);
        InPlaceWrites.rewrite(file, yaml ? "flagValues: [\n" : "{\"flagValues\": ", () -> { });
        assertLoadFailsFor(loader, file);
        for (int i = 0; i < 6; i++) {
          clock.advance(1);
          assertLoadFailsFor(loader, file);
        }

        InPlaceWrites.truncate(file);
        assertHeld(loader, file);
        InPlaceWrites.rewrite(file, yaml ? "flagValues:\n  flag: \"fixed\"\n" : "{\"flagValues\":{\"flag\":\"fixed\"}}",
            () -> { });
        assertEquals(name, 1, loader.load(RETRY).getFlagCount());
      }
    }
  }

  @Test
  public void fileThatFailedToParseStartsANewDelayWhenItIsEmptiedAgain() throws Exception {
    // Content that fails to parse also ends the file's empty state, so an emptying after it starts a
    // new delay, even when a coarse clock gives it the state of the earlier emptying.
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", YAML_DOC);
      TestClock clock = new TestClock();
      OverrideFileLoader loader = loader(clock, file);
      loadAndAccept(loader);

      InPlaceWrites.truncate(file);
      java.nio.file.attribute.FileTime emptiedAt = Files.getLastModifiedTime(file);
      assertHeld(loader, file);
      clock.advance(0.5);
      InPlaceWrites.rewrite(file, "flagValues: [\n", () -> { });
      assertLoadFailsFor(loader, file);
      clock.advance(0.7);
      InPlaceWrites.truncate(file);
      Files.setLastModifiedTime(file, emptiedAt);
      assertHeld(loader, file);
    }
  }

  @Test
  public void fileThatFailsToReadStartsItsBoundOver() throws Exception {
    // A load that fails because a file cannot be read holds nothing back, so it starts that file's
    // bound over too.
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", YAML_DOC);
      TestClock clock = new TestClock();
      AtomicLong unreadable = new AtomicLong();
      OverrideFileLoader loader = new OverrideFileLoader(Collections.singletonList(file),
          FileData.DuplicateKeysHandling.FAIL, clock.nanos::get, path -> {
            if (unreadable.get() != 0) {
              throw new java.io.IOException("permission denied");
            }
            return Files.readAllBytes(path);
          });
      loadAndAccept(loader);

      InPlaceWrites.truncate(file);
      assertHeld(loader, file);
      unreadable.set(1);
      for (int i = 0; i < 6; i++) {
        clock.advance(1);
        assertLoadFailsFor(loader, file);
      }
      unreadable.set(0);
      writeAgain(file, " ");
      assertHeld(loader, file);
    }
  }

  @Test
  public void boundOfAFileAfterAFailingFileStartsOverOnceItIsReadAgain() throws Exception {
    // c.json comes first. While it fails, a.yaml, which was held back, gets its content; the failing
    // loads still read a.yaml, so its bound starts over, and the in-place rewrite of a.yaml that comes
    // with the fix of c.json is held back.
    try (TempDir dir = TempDir.create()) {
      String other = "{\"flagValues\":{\"other\":\"value\"}}";
      Path c = write(dir, "c.json", other);
      Path a = write(dir, "a.yaml", YAML_DOC);
      TestClock clock = new TestClock();
      OverrideFileLoader loader = loader(clock, c, a);
      loadAndAccept(loader);

      InPlaceWrites.truncate(a);
      assertHeld(loader, a);
      InPlaceWrites.rewrite(c, "{bad", () -> { });
      InPlaceWrites.rewrite(a, YAML_DOC, () -> { });
      for (int i = 0; i < 6; i++) {
        assertLoadFailsFor(loader, c);
        clock.advance(1);
      }
      InPlaceWrites.rewrite(c, other, () -> { });
      InPlaceWrites.truncate(a);
      assertHeld(loader, a);
    }
  }

  @Test
  public void failingLoadReportsTheFirstFailure() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path first = write(dir, "first.json", "{bad");
      Path second = write(dir, "second.yaml", "flagValues: [\n");
      try {
        loader(first, second).load(RETRY);
        fail("expected exception");
      } catch (FileDataException e) {
        assertThat(e.getMessage(), containsString(first + ": cannot parse JSON"));
      }
    }
  }

  @Test
  public void fileAfterADuplicateKeyIsReadSoItsBoundStartsOver() throws Exception {
    // The entries are merged only after every file is read, so a duplicate key between a.yaml and
    // b.yaml does not keep the loads from reading c.yaml, whose bound starts over while they fail.
    try (TempDir dir = TempDir.create()) {
      Path a = write(dir, "a.yaml", "flagValues:\n  k: \"a\"\n");
      String bDoc = "flagValues:\n  j: \"b\"\n";
      Path b = write(dir, "b.yaml", bDoc);
      Path c = write(dir, "c.yaml", YAML_DOC);
      TestClock clock = new TestClock();
      OverrideFileLoader loader = loader(clock, a, b, c);
      loadAndAccept(loader);

      InPlaceWrites.truncate(c);
      assertHeld(loader, c);
      InPlaceWrites.rewrite(b, "flagValues:\n  j: \"b\"\n  k: \"dup\"\n", () -> { });
      InPlaceWrites.rewrite(c, YAML_DOC, () -> { });
      for (int i = 0; i < 8; i++) {
        clock.advance(1);
        try {
          loader.load(RETRY);
          fail("expected exception");
        } catch (FileDataException e) {
          assertThat(e.getMessage(), containsString("key \"k\" was already defined"));
        }
      }
      InPlaceWrites.rewrite(b, bDoc, () -> { });
      truncateAgain(c);
      assertHeld(loader, c);
    }
  }

  @Test
  public void fixOfAFileSavedInPlaceWithADuplicateKeyIsHeldBack() throws Exception {
    // As with a parse error: b.yaml is saved in place with a key that a.yaml defines, then the fix is
    // saved in place several retry delays later. The fix's gap is held back.
    try (TempDir dir = TempDir.create()) {
      Path a = write(dir, "a.yaml", "flagValues:\n  k: \"a\"\n");
      Path b = write(dir, "b.yaml", "flagValues:\n  j: \"b\"\n");
      TestClock clock = new TestClock();
      OverrideFileLoader loader = loader(clock, a, b);
      loadAndAccept(loader);

      InPlaceWrites.truncate(b);
      assertHeld(loader, b);
      InPlaceWrites.rewrite(b, "flagValues:\n  j: \"b\"\n  k: \"dup\"\n", () -> { });
      for (int i = 0; i < 7; i++) {
        try {
          loader.load(RETRY);
          fail("expected exception");
        } catch (FileDataException e) {
          assertThat(e.getMessage(), containsString("key \"k\" was already defined"));
        }
        clock.advance(1);
      }
      InPlaceWrites.truncate(b);
      assertHeld(loader, b);
    }
  }

  @Test
  public void slowReadOfTheEmptiedFileItselfDoesNotShortenItsDelay() throws Exception {
    // The emptied file's own read stalls for most of a retry delay, as on a network file system. Its
    // delay counts from after its read.
    try (TempDir dir = TempDir.create()) {
      Path a = write(dir, "a.yaml", YAML_DOC);
      TestClock clock = new TestClock();
      AtomicLong slowRead = new AtomicLong();
      OverrideFileLoader loader = new OverrideFileLoader(Collections.singletonList(a),
          FileData.DuplicateKeysHandling.FAIL, clock.nanos::get, path -> {
            byte[] raw = Files.readAllBytes(path);
            if (slowRead.getAndSet(0) != 0) {
              clock.advance(0.9);
            }
            return raw;
          });
      loadAndAccept(loader);

      InPlaceWrites.truncate(a);
      slowRead.set(1);
      assertHeld(loader, a);
      clock.advance(0.2);
      assertHeld(loader, a);
    }
  }

  @Test
  public void jsonDocumentThatIsNotAnObjectFailsToParse() throws Exception {
    try (TempDir dir = TempDir.create()) {
      for (String doc : new String[] { "null", "[]", "5", "\"x\"" }) {
        Path file = write(dir, "overrides.json", doc);
        try {
          loader(file).load(Duration.ZERO);
          fail("expected exception for " + doc);
        } catch (FileDataException e) {
          assertEquals("file " + file + ": the JSON document is not an object", e.getMessage());
          assertNull(e.getCause());
        }
      }
    }
  }

  @Test
  public void textEncodingIsDetectedFromAByteOrderMarkAndIsUtf8Otherwise() throws Exception {
    // Applies to both formats. Without a byte order mark the text is UTF-8, whatever the platform's
    // default encoding.
    String[] names = { "overrides.json", "overrides.yaml" };
    java.nio.charset.Charset[] charsets = { StandardCharsets.UTF_8, StandardCharsets.UTF_16LE,
        StandardCharsets.UTF_16BE };
    try (TempDir dir = TempDir.create()) {
      for (String name : names) {
        String doc = name.endsWith(".json") ? "{\"flagValues\":{\"flag\":\"caf\u00e9\"}}"
            : "flagValues:\n  flag: \"caf\u00e9\"\n";
        for (java.nio.charset.Charset charset : charsets) {
          for (boolean bom : new boolean[] { true, false }) {
            if (!bom && charset != StandardCharsets.UTF_8) {
              continue;
            }
            Path file = dir.getPath().resolve(name);
            Files.write(file, ((bom ? "\uFEFF" : "") + doc).getBytes(charset));
            LoadResult result = loader(file).load(Duration.ZERO);
            String what = name + " " + charset + (bom ? " with a byte order mark" : "");
            assertEquals(what, LDValue.buildArray().add("caf\u00e9").build(),
                json(FEATURES, toMap(result).get(FEATURES).get("flag")).get("variations"));
          }
        }
      }
    }
  }

  @Test
  public void utf16FileWithOnlyWhiteSpaceCountsAsEmpty() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", YAML_DOC);
      OverrideFileLoader loader = loader(new TestClock(), file);
      loadAndAccept(loader);

      Files.write(file, "\uFEFF \n".getBytes(StandardCharsets.UTF_16LE));
      assertHeld(loader, file);
    }
  }

  @Test
  public void contentHashCoversPathsAndPresence() throws Exception {
    // The format depends on the path, so the same bytes moved from one configured file to another
    // can be different data.
    try (TempDir dir = TempDir.create()) {
      Path yaml = write(dir, "overrides.yaml", "{\"flagValues\": {\"flag\": on}}");
      Path json = dir.getPath().resolve("overrides.json");
      OverrideFileLoader loader = loader(yaml, json);
      LoadResult before = loader.load(Duration.ZERO);
      Files.move(yaml, json);
      LoadResult after = loader.load(Duration.ZERO);
      assertThat(Arrays.equals(before.getContentHash(), after.getContentHash()), is(false));
      assertEquals(LDValue.of(true), json(FEATURES, toMap(before).get(FEATURES).get("flag")).get("variations").get(0));
      assertEquals(LDValue.of("on"), json(FEATURES, toMap(after).get(FEATURES).get("flag")).get("variations").get(0));
    }
  }

  // Loads the files and applies the result, as the reloader does, or reports that the load held a
  // file back or failed.
  private static boolean loadAppliesOrFails(OverrideFileLoader loader) throws Exception {
    try {
      loader.accepted(loader.load(RETRY));
      return true;
    } catch (EmptiedFileException | FileDataException e) {
      return false;
    }
  }

  @Test
  public void loadThatHoldsAFileBackAndThenFailsDoesNotStartBoundsOver() throws Exception {
    // a.yaml and b.yaml are rewritten in turn, so that every load holds one of them back, and c.json
    // fails at every other load. Those loads hold a file back, so they start no bound over.
    try (TempDir dir = TempDir.create()) {
      Path a = write(dir, "a.yaml", YAML_DOC);
      String bDoc = "flagValues:\n  other: \"value\"\n";
      Path b = write(dir, "b.yaml", bDoc);
      String cGood = "{\"flagValues\":{\"third\":\"value\"}}";
      Path c = write(dir, "c.json", cGood);
      TestClock clock = new TestClock();
      OverrideFileLoader loader = loader(clock, a, b, c);
      loadAndAccept(loader);

      InPlaceWrites.truncate(a);
      assertHeld(loader, a);
      boolean applied = false;
      for (int i = 1; i <= 12 && !applied; i++) {
        clock.advance(0.5);
        boolean odd = i % 2 == 1;
        InPlaceWrites.rewrite(odd ? a : b, odd ? YAML_DOC : bDoc, () -> { });
        InPlaceWrites.truncate(odd ? b : a);
        InPlaceWrites.rewrite(c, odd ? "{bad" : cGood, () -> { });
        applied = loadAppliesOrFails(loader);
      }
      assertTrue("the files were held back without end", applied);
    }
  }

  @Test
  public void fileKeptWrittenWhileEmptyIsAppliedAfterItsBoundWhileAnEarlierFileFails() throws Exception {
    // c.json comes first and fails now and then, while a.yaml keeps being written while empty. The
    // failing loads still read a.yaml and hold it back, so they do not start its bound over.
    try (TempDir dir = TempDir.create()) {
      String other = "{\"flagValues\":{\"other\":\"value\"}}";
      Path c = write(dir, "c.json", other);
      Path a = write(dir, "a.yaml", YAML_DOC);
      TestClock clock = new TestClock();
      OverrideFileLoader loader = loader(clock, c, a);
      loadAndAccept(loader);

      InPlaceWrites.truncate(a);
      assertHeld(loader, a);
      for (int i = 1; i <= 5; i++) {
        clock.advance(0.9);
        writeAgain(a, i % 2 == 0 ? " " : "  ");
        if (i % 2 == 1) {
          InPlaceWrites.rewrite(c, "{bad", () -> { });
          assertLoadFailsFor(loader, c);
        } else {
          InPlaceWrites.rewrite(c, other, () -> { });
          assertHeld(loader, a);
        }
      }
      clock.advance(0.9);
      writeAgain(a, " ");
      InPlaceWrites.rewrite(c, other, () -> { });
      assertEquals(1, loader.load(RETRY).getFlagCount());
    }
  }

  @Test
  public void holdAsksForTheRetryFromTheEndOfTheLoad() throws Exception {
    // The read of the file after the held one takes longer than what is left of the held file's
    // delay, so the retry is due right away, after the margin.
    try (TempDir dir = TempDir.create()) {
      Path a = write(dir, "a.yaml", YAML_DOC);
      Path b = write(dir, "b.yaml", "flagValues:\n  other: \"value\"\n");
      TestClock clock = new TestClock();
      AtomicLong slowReadOf = new AtomicLong();
      OverrideFileLoader loader = new OverrideFileLoader(Arrays.asList(a, b), FileData.DuplicateKeysHandling.FAIL,
          clock.nanos::get, path -> {
            byte[] raw = Files.readAllBytes(path);
            if (path.equals(b) && slowReadOf.getAndSet(0) != 0) {
              clock.advance(0.1);
            }
            return raw;
          });
      loadAndAccept(loader);

      InPlaceWrites.truncate(a);
      assertEquals(1.1, assertHeld(loader, a), 1e-9);
      clock.advance(0.95);
      slowReadOf.set(1);
      assertEquals(0.1, assertHeld(loader, a), 1e-9);
    }
  }

  private static void assertLoadFailsFor(OverrideFileLoader loader, Path file) throws Exception {
    try {
      loader.load(RETRY);
      fail("expected the load to fail");
    } catch (FileDataException e) {
      assertThat(e.getMessage(), containsString(file.toString()));
    }
  }

  @Test
  public void anotherFilesBoundDoesNotCutShortARewrite() throws Exception {
    // b.yaml keeps being written while empty, and reaches its bound while a.yaml is in the middle of an
    // ordinary rewrite. a.yaml is still held back.
    try (TempDir dir = TempDir.create()) {
      Path a = write(dir, "a.yaml", YAML_DOC);
      Path b = write(dir, "b.yaml", "flagValues:\n  other: \"value\"\n");
      TestClock clock = new TestClock();
      OverrideFileLoader loader = loader(clock, a, b);
      loadAndAccept(loader);

      InPlaceWrites.truncate(b);
      assertHeld(loader, b);
      for (int i = 1; i <= 9; i++) {
        clock.advance(0.5);
        writeAgain(b, " ");
        if (i == 9) {
          InPlaceWrites.truncate(a);
          assertHeld(loader, a, b);
        } else {
          assertHeld(loader, b);
        }
      }
      // b's bound is over now, but a has been empty for half a retry delay only.
      clock.advance(0.5);
      writeAgain(b, "  ");
      assertHeld(loader, a);

      clock.advance(0.25);
      InPlaceWrites.rewrite(a, YAML_DOC, () -> { });
      LoadResult result = loader.load(RETRY);
      assertEquals(1, result.getFlagCount());
      assertTrue(result.getFiles().get(0).hasContent());
    }
  }

  @Test
  public void holdAsksForTheRestOfTheDelayAfterASlowReadOfAnotherFile() throws Exception {
    // a.yaml is held back, and the read of b.yaml after it takes half a retry delay. The retry comes
    // when a.yaml's delay is over, counted from when the load asks for it.
    try (TempDir dir = TempDir.create()) {
      Path a = write(dir, "a.yaml", YAML_DOC);
      Path b = write(dir, "b.yaml", "flagValues:\n  other: \"value\"\n");
      TestClock clock = new TestClock();
      AtomicLong slowReadOf = new AtomicLong();
      OverrideFileLoader loader = new OverrideFileLoader(Arrays.asList(a, b), FileData.DuplicateKeysHandling.FAIL,
          clock.nanos::get, path -> {
            byte[] raw = Files.readAllBytes(path);
            if (path.equals(b) && slowReadOf.getAndSet(0) != 0) {
              clock.advance(0.5);
            }
            return raw;
          });
      loadAndAccept(loader);

      InPlaceWrites.truncate(a);
      slowReadOf.set(1);
      assertEquals(0.6, assertHeld(loader, a), 1e-9);
    }
  }

  @Test
  public void slowReadOfAnotherFileDoesNotShortenTheDelay() throws Exception {
    // The read of b.yaml, which comes first, takes most of a retry delay. a.yaml's delay counts from
    // when a.yaml was read.
    try (TempDir dir = TempDir.create()) {
      Path a = write(dir, "a.yaml", YAML_DOC);
      Path b = write(dir, "b.yaml", "flagValues:\n  other: \"value\"\n");
      TestClock clock = new TestClock();
      AtomicLong slowReadOf = new AtomicLong();
      OverrideFileLoader loader = new OverrideFileLoader(Arrays.asList(b, a), FileData.DuplicateKeysHandling.FAIL,
          clock.nanos::get, path -> {
            byte[] raw = Files.readAllBytes(path);
            if (path.equals(b) && slowReadOf.getAndSet(0) != 0) {
              clock.advance(0.94);
            }
            return raw;
          });
      loadAndAccept(loader);

      InPlaceWrites.truncate(a);
      slowReadOf.set(1);
      assertHeld(loader, a);
      clock.advance(0.1);
      assertHeld(loader, a);
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
  public void filesEmptiedTogetherAreHeldFromTheSameLoad() throws Exception {
    // A load that holds back one file still reads the others, so each emptied file starts its delay
    // in that load and all of them are parsed after one retry delay, not one retry delay per file.
    try (TempDir dir = TempDir.create()) {
      Path first = write(dir, "first.yaml", YAML_DOC);
      Path second = write(dir, "second.yaml", "flagValues:\n  other: \"value\"\n");
      TestClock clock = new TestClock();
      OverrideFileLoader loader = loader(clock, first, second);
      assertEquals(2, loadAndAccept(loader).getFlagCount());

      InPlaceWrites.truncate(first);
      InPlaceWrites.truncate(second);
      assertHeld(loader, first, second);
      clock.advance(1);
      assertEquals(0, loader.load(RETRY).getFlagCount());
    }
  }

  @Test
  public void failureOfAnotherFileFailsALoadThatHoldsAFile() throws Exception {
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
