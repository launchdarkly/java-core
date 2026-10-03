package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.sdk.LDValue;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
      LoadResult result = loader(file).load();

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
      LoadResult result = loader(file).load();
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
  public void yamlIsAutoDetected() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", "flagValues:\n  yaml-flag: \"override-value\"\n");
      LoadResult result = loader(file).load();
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
      LoadResult result = loader(present, missing, segments).load();

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
      LoadResult result = loader(dir.getPath().resolve("a.json"), dir.getPath().resolve("b.json")).load();
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
        loader(directory).load();
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
        loader(good, bad).load();
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
        loader(file).load();
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
        loader(a, b).load();
        fail("expected exception");
      } catch (FileDataException e) {
        assertThat(e.getMessage(), containsString("in features, key \"flag\" was already defined"));
        assertThat(e.getMessage(), containsString(b.toString()));
        assertNull(e.getCause());
      }

      Path both = write(dir, "both.json", "{\"flags\":{\"x\":{\"key\":\"x\"}},\"flagValues\":{\"x\":true}}");
      try {
        loader(both).load();
        fail("expected exception");
      } catch (FileDataException e) {
        assertThat(e.getMessage(), containsString("key \"x\" was already defined"));
      }

      Path seg1 = write(dir, "seg1.json", "{\"segments\":{\"s\":{\"key\":\"s\"}}}");
      Path seg2 = write(dir, "seg2.json", "{\"segments\":{\"s\":{\"key\":\"s\"}}}");
      try {
        loader(seg1, seg2).load();
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
      LoadResult result = loader.load();

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
      byte[] first = loader.load().getContentHash();
      byte[] second = loader.load().getContentHash();
      assertArrayEquals(first, second);

      Files.write(file, "{\"flagValues\":{\"a\":2}}".getBytes());
      byte[] third = loader.load().getContentHash();
      assertThat(Arrays.equals(first, third), is(false));

      // A file that appears changes the digest.
      Files.write(missing, "{}".getBytes());
      byte[] fourth = loader.load().getContentHash();
      assertThat(Arrays.equals(third, fourth), is(false));
    }
  }

  @Test
  public void emptyDocumentContributesNothing() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "empty.json", "{}");
      LoadResult result = loader(file).load();
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
          json(FEATURES, toMap(loader.load()).get(FEATURES).get("k")).get("variations"));
      assertEquals(Collections.singletonList(b).get(0), loader.load().getFiles().get(0).getPath());
    }
  }
}
