package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.logging.LDLogLevel;
import com.launchdarkly.logging.LogCapture;
import com.launchdarkly.logging.Logs;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.Components;
import com.launchdarkly.sdk.server.LDConfig;
import com.launchdarkly.sdk.server.TestComponents;
import com.launchdarkly.sdk.server.integrations.FileOverrides.ChangeDetection;
import com.launchdarkly.sdk.server.subsystems.ClientContext;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;
import com.launchdarkly.sdk.server.subsystems.OverrideSink;
import com.launchdarkly.sdk.server.subsystems.OverrideSource;
import com.launchdarkly.testhelpers.TempDir;

import org.junit.After;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.launchdarkly.sdk.server.DataModel.FEATURES;
import static com.launchdarkly.sdk.server.DataModel.SEGMENTS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@SuppressWarnings("javadoc")
public class FileOverrideSourceTest {
  private static final long WAIT_MILLIS = 10000;

  private final LogCapture logCapture = Logs.capture();
  private final ClientContext context = TestComponents.clientContext("sdk-key",
      new LDConfig.Builder().logging(Components.logging(logCapture).level(LDLogLevel.DEBUG)).build());
  private final List<OverrideSource> sources = new ArrayList<>();

  @After
  public void closeSources() throws Exception {
    for (OverrideSource s : sources) {
      s.close();
    }
  }

  /**
   * Records every snapshot the source supplies, as a map of kind name to key to item.
   */
  static final class RecordingSink implements OverrideSink {
    final BlockingQueue<Map<String, Map<String, ItemDescriptor>>> snapshots = new LinkedBlockingQueue<>();

    @Override
    public void setOverrides(Iterable<Map.Entry<DataKind, KeyedItems<ItemDescriptor>>> data) {
      Map<String, Map<String, ItemDescriptor>> snapshot = new HashMap<>();
      snapshot.put(FEATURES.getName(), new HashMap<>());
      snapshot.put(SEGMENTS.getName(), new HashMap<>());
      for (Map.Entry<DataKind, KeyedItems<ItemDescriptor>> kind : data) {
        Map<String, ItemDescriptor> items = snapshot.computeIfAbsent(kind.getKey().getName(), k -> new HashMap<>());
        for (Map.Entry<String, ItemDescriptor> item : kind.getValue().getItems()) {
          items.put(item.getKey(), item.getValue());
        }
      }
      snapshots.add(snapshot);
    }

    Map<String, Map<String, ItemDescriptor>> await() throws InterruptedException {
      Map<String, Map<String, ItemDescriptor>> s = snapshots.poll(WAIT_MILLIS, TimeUnit.MILLISECONDS);
      if (s == null) {
        throw new AssertionError("timed out waiting for an override snapshot");
      }
      return s;
    }

    void assertNoSnapshot(long millis) throws InterruptedException {
      assertNull(snapshots.poll(millis, TimeUnit.MILLISECONDS));
    }
  }

  private OverrideSource start(FileOverrideSourceBuilder builder, RecordingSink sink) {
    OverrideSource source = builder.build(context);
    sources.add(source);
    source.start(sink);
    return source;
  }

  private static String flagValue(Map<String, Map<String, ItemDescriptor>> snapshot, String key) {
    ItemDescriptor item = snapshot.get(FEATURES.getName()).get(key);
    if (item == null) {
      return null;
    }
    LDValue json = LDValue.parse(FEATURES.serialize(item));
    return json.get("variations").get(0).stringValue();
  }

  private static Path write(TempDir dir, String name, String contents) throws Exception {
    Path p = dir.getPath().resolve(name);
    Files.write(p, contents.getBytes());
    return p;
  }

  private static String docWith(String key, String value) {
    return "{\"flagValues\":{\"" + key + "\":\"" + value + "\"}}";
  }

  @Test
  public void buildRequiresFilePaths() {
    try {
      FileOverrides.source().build(context);
      fail("expected exception");
    } catch (IllegalArgumentException e) {
      assertThat(e.getMessage(), containsString("no file paths"));
    }
  }

  @Test
  public void buildRequiresChangeDetectionMode() {
    try {
      FileOverrides.source().filePaths("x.json").changeDetection(null).build(context);
      fail("expected exception");
    } catch (IllegalArgumentException e) {
      assertThat(e.getMessage(), containsString("change detection"));
    }
  }

  @Test
  public void buildRequiresPollInterval() {
    try {
      FileOverrides.source().filePaths("x.json").pollInterval(null).build(context);
      fail("expected exception");
    } catch (IllegalArgumentException e) {
      assertThat(e.getMessage(), containsString("poll interval"));
    }
  }

  @Test
  public void nullDuplicateKeysHandlingSelectsFail() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path a = write(dir, "a.json", docWith("flag", "a"));
      Path b = write(dir, "b.json", docWith("flag", "b"));
      RecordingSink sink = new RecordingSink();
      start(FileOverrides.source().filePaths(a, b).duplicateKeysHandling(null), sink);
      sink.assertNoSnapshot(200);
      assertThat(logCapture.getMessageStrings(), hasItem(allOf(startsWith("ERROR:Unable to load flags: "),
          containsString("in features, key \"flag\" was already defined"), containsString(b.toString()))));
    }
  }

  @Test
  public void initialLoadCompletesBeforeStartReturns() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.json",
          "{\"flags\":{\"full\":{\"key\":\"full\",\"version\":7,\"on\":true,\"variations\":[\"x\"],\"fallthrough\":{\"variation\":0}}},"
          + "\"flagValues\":{\"simple\":\"value\"},"
          + "\"segments\":{\"seg\":{\"key\":\"seg\",\"version\":3,\"included\":[\"u\"]}}}");
      RecordingSink sink = new RecordingSink();
      start(FileOverrides.source().filePaths(file), sink);

      Map<String, Map<String, ItemDescriptor>> snapshot = sink.snapshots.poll();
      assertNotNull("the initial snapshot must be supplied before start returns", snapshot);
      assertEquals(7, snapshot.get(FEATURES.getName()).get("full").getVersion());
      assertEquals("value", flagValue(snapshot, "simple"));
      // A value-only entry is a flag served by fallthrough with version 0.
      ItemDescriptor simple = snapshot.get(FEATURES.getName()).get("simple");
      assertEquals(0, simple.getVersion());
      LDValue simpleJson = LDValue.parse(FEATURES.serialize(simple));
      assertEquals(LDValue.of(true), simpleJson.get("on"));
      assertEquals(LDValue.of(0), simpleJson.get("fallthrough").get("variation"));
      assertEquals(LDValue.ofNull(), simpleJson.get("offVariation"));
      assertEquals(3, snapshot.get(SEGMENTS.getName()).get("seg").getVersion());
      assertThat(logCapture.getMessageStrings(), hasItem(
          "INFO:Flag overrides in effect: 2 flags, 1 segment (" + file + ": 2 flags, 1 segment)"));
    }
  }

  @Test
  public void loadsYamlDocument() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", "flagValues:\n  yaml-flag: \"override-value\"\n");
      RecordingSink sink = new RecordingSink();
      start(FileOverrides.source().filePaths(file), sink);
      assertEquals("override-value", flagValue(sink.await(), "yaml-flag"));
    }
  }

  @Test
  public void mergesFilesInConfiguredOrderWithIgnoreHandling() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path first = write(dir, "first.json", docWith("shared", "first"));
      Path second = write(dir, "second.json", "{\"flagValues\":{\"shared\":\"second\",\"only-second\":\"x\"}}");
      RecordingSink sink = new RecordingSink();
      start(FileOverrides.source().filePaths(first, second)
          .duplicateKeysHandling(FileData.DuplicateKeysHandling.IGNORE), sink);

      Map<String, Map<String, ItemDescriptor>> snapshot = sink.await();
      assertEquals("first", flagValue(snapshot, "shared"));
      assertEquals("x", flagValue(snapshot, "only-second"));
      assertThat(logCapture.getMessageStrings(), hasItem(
          "INFO:Flag overrides in effect: 2 flags (" + first + ": 1 flag; " + second + ": 1 flag)"));
    }
  }

  @Test
  public void duplicateKeysFailTheLoadByDefault() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path a = write(dir, "a.json", docWith("flag", "a"));
      Path b = write(dir, "b.json", docWith("flag", "b"));
      RecordingSink sink = new RecordingSink();
      start(FileOverrides.source().filePaths(a, b), sink);
      sink.assertNoSnapshot(200);
    }
  }

  @Test
  public void missingFileContributesNoEntriesAndIsNotAnError() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path present = write(dir, "present.json", docWith("flag", "value"));
      Path missing = dir.getPath().resolve("missing.json");
      RecordingSink sink = new RecordingSink();
      start(FileOverrides.source().filePaths(present, missing), sink);

      Map<String, Map<String, ItemDescriptor>> snapshot = sink.await();
      assertEquals("value", flagValue(snapshot, "flag"));
      assertEquals(1, snapshot.get(FEATURES.getName()).size());
      for (String message : logCapture.getMessageStrings()) {
        assertTrue("unexpected error log: " + message, !message.startsWith("ERROR:"));
      }
      assertThat(logCapture.getMessageStrings(), hasItem(
          "INFO:Flag overrides in effect: 1 flag (" + present + ": 1 flag; " + missing + ": absent)"));
    }
  }

  @Test
  public void startsWithNoFilesAndLogsNoneInEffect() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path missing = dir.getPath().resolve("missing.json");
      RecordingSink sink = new RecordingSink();
      start(FileOverrides.source().filePaths(missing), sink);

      Map<String, Map<String, ItemDescriptor>> snapshot = sink.await();
      assertTrue(snapshot.get(FEATURES.getName()).isEmpty());
      assertThat(logCapture.getMessageStrings(), hasItem("INFO:Flag overrides: none in effect (" + missing + ": absent)"));
    }
  }

  @Test
  public void emptyDocumentLogsNoEntries() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "empty.json", "{}");
      RecordingSink sink = new RecordingSink();
      start(FileOverrides.source().filePaths(file), sink);
      sink.await();
      assertThat(logCapture.getMessageStrings(), hasItem("INFO:Flag overrides: none in effect (" + file + ": no entries)"));
    }
  }

  @Test
  public void malformedFileAtStartupSuppliesNothingAndLogsError() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "bad.json", "{\"flagValues\"");
      RecordingSink sink = new RecordingSink();
      start(FileOverrides.source().filePaths(file), sink);
      sink.assertNoSnapshot(200);
      assertThat(logCapture.getMessageStrings(), hasItem(startsWith("ERROR:Unable to load flags:")));
    }
  }

  private void reloadsOnChange(ChangeDetection mode) throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.json", docWith("flag", "first"));
      RecordingSink sink = new RecordingSink();
      start(FileOverrides.source().filePaths(file).changeDetection(mode).pollInterval(Duration.ofSeconds(1)), sink);
      assertEquals("first", flagValue(sink.await(), "flag"));

      // A changed file is reloaded.
      Files.write(file, docWith("flag", "second").getBytes());
      assertEquals("second", flagValue(sink.await(), "flag"));

      // A malformed edit keeps the last good overrides and supplies nothing.
      Files.write(file, "{\"flagValues\"".getBytes());
      sink.assertNoSnapshot(1500);
      assertThat(logCapture.getMessageStrings(), hasItem(startsWith("ERROR:Unable to load flags:")));

      // A later good edit is applied.
      Files.write(file, docWith("flag", "third").getBytes());
      assertEquals("third", flagValue(sink.await(), "flag"));

      // Deleting the file removes its overrides.
      Files.delete(file);
      assertTrue(sink.await().get(FEATURES.getName()).isEmpty());

      // Recreating the file brings them back.
      Files.write(file, docWith("flag", "fourth").getBytes());
      assertEquals("fourth", flagValue(sink.await(), "flag"));
    }
  }

  @Test
  public void pollingModeReloadsOnChange() throws Exception {
    reloadsOnChange(ChangeDetection.POLLING);
  }

  @Test
  public void watchingModeReloadsOnChange() throws Exception {
    reloadsOnChange(ChangeDetection.WATCHING);
  }

  @Test
  public void fileThatDoesNotExistYetTakesEffectWhenItAppears() throws Exception {
    for (ChangeDetection mode : ChangeDetection.values()) {
      try (TempDir dir = TempDir.create()) {
        Path file = dir.getPath().resolve("later.json");
        RecordingSink sink = new RecordingSink();
        start(FileOverrides.source().filePaths(file).changeDetection(mode), sink);
        assertTrue(sink.await().get(FEATURES.getName()).isEmpty());

        Files.write(file, docWith("flag", "appeared").getBytes());
        assertEquals(mode.toString(), "appeared", flagValue(sink.await(), "flag"));
      }
    }
  }

  @Test
  public void pollIntervalBelowMinimumIsRaisedWithWarning() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.json", "{}");
      RecordingSink sink = new RecordingSink();
      OverrideSource source = start(FileOverrides.source().filePaths(file).pollInterval(Duration.ofMillis(10)), sink);
      assertEquals(FileOverrides.MINIMUM_POLL_INTERVAL, ((FileOverrideSourceImpl) source).getPollInterval());
      assertThat(logCapture.getMessageStrings(), hasItem(
          "WARN:Poll interval PT0.01S is below the minimum; using PT1S"));
    }
  }

  @Test
  public void pollIntervalAtOrAboveMinimumIsKept() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.json", "{}");
      OverrideSource source = start(FileOverrides.source().filePaths(file).pollInterval(Duration.ofSeconds(3)),
          new RecordingSink());
      assertEquals(Duration.ofSeconds(3), ((FileOverrideSourceImpl) source).getPollInterval());
      OverrideSource defaulted = start(FileOverrides.source().filePaths(file), new RecordingSink());
      assertEquals(FileOverrides.DEFAULT_POLL_INTERVAL, ((FileOverrideSourceImpl) defaulted).getPollInterval());
    }
  }

  @Test
  public void failedLoadIsRetriedWithoutAFileChange() throws Exception {
    // Watching mode delivers no notification while the file is untouched, so only the source's own
    // retry can attempt the load again.
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "bad.json", "{\"flagValues\"");
      RecordingSink sink = new RecordingSink();
      start(FileOverrides.source().filePaths(file).changeDetection(ChangeDetection.WATCHING), sink);
      assertThat(logCapture.getMessageStrings(), hasItem(startsWith("ERROR:Unable to load flags:")));

      long deadline = System.currentTimeMillis() + WAIT_MILLIS;
      while (!logCapture.getMessageStrings().contains("DEBUG:Retrying flag data load after earlier failure")) {
        if (System.currentTimeMillis() > deadline) {
          fail("the failed load was not retried");
        }
        Thread.sleep(50);
      }
      sink.assertNoSnapshot(100);
    }
  }

  @Test
  public void watchingModeIgnoresPollIntervalMinimum() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.json", "{}");
      RecordingSink sink = new RecordingSink();
      start(FileOverrides.source().filePaths(file).changeDetection(ChangeDetection.WATCHING)
          .pollInterval(Duration.ofMillis(10)), sink);
      for (String message : logCapture.getMessageStrings()) {
        assertTrue(message, !message.startsWith("WARN:Poll interval"));
      }
    }
  }

  private static int liveThreadsNamed(String prefix) {
    int count = 0;
    for (Thread t : Thread.getAllStackTraces().keySet()) {
      if (t.isAlive() && t.getName().startsWith(prefix)) {
        count++;
      }
    }
    return count;
  }

  private static void awaitLiveThreadsNamed(String prefix, int expected) throws InterruptedException {
    long deadline = System.currentTimeMillis() + WAIT_MILLIS;
    while (liveThreadsNamed(prefix) != expected) {
      if (System.currentTimeMillis() > deadline) {
        fail("expected " + expected + " live threads named " + prefix + " but found " + liveThreadsNamed(prefix));
      }
      Thread.sleep(20);
    }
  }

  private void closeReleasesThreads(ChangeDetection mode, String threadPrefix) throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.json", docWith("flag", "first"));
      int baseline = liveThreadsNamed(threadPrefix);
      RecordingSink sink = new RecordingSink();
      OverrideSource source = start(FileOverrides.source().filePaths(file).changeDetection(mode)
          .pollInterval(Duration.ofSeconds(1)), sink);
      sink.await();
      awaitLiveThreadsNamed(threadPrefix, baseline + 1);

      source.close();
      source.close();

      awaitLiveThreadsNamed(threadPrefix, baseline);
      Files.write(file, docWith("flag", "second").getBytes());
      sink.assertNoSnapshot(1500);
    }
  }

  @Test
  public void closeIsIdempotentAndStopsThePoller() throws Exception {
    closeReleasesThreads(ChangeDetection.POLLING, "LaunchDarkly-FileDataPoller");
  }

  @Test
  public void closeIsIdempotentAndStopsTheWatcher() throws Exception {
    closeReleasesThreads(ChangeDetection.WATCHING, "LaunchDarkly-FileDataWatcher");
  }

  @Test
  public void countsTextFormatsCounts() {
    assertEquals("1 flag", FileOverrideSourceImpl.countsText(1, 0));
    assertEquals("2 flags, 1 segment", FileOverrideSourceImpl.countsText(2, 1));
    assertEquals("3 segments", FileOverrideSourceImpl.countsText(0, 3));
    assertEquals("", FileOverrideSourceImpl.countsText(0, 0));
  }

  @Test
  public void relativePathIsResolvedAgainstWorkingDirectory() throws Exception {
    Path relative = java.nio.file.Paths.get("no-such-dir-for-override-test", "overrides.json");
    RecordingSink sink = new RecordingSink();
    start(FileOverrides.source().filePaths(relative.toString()), sink);
    assertTrue(sink.await().get(FEATURES.getName()).isEmpty());
    assertThat(logCapture.getMessageStrings(), hasItem(containsString(relative.toAbsolutePath().normalize().toString())));
  }

  @Test
  public void loggerUsesSubLoggerName() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.json", "{}");
      start(FileOverrides.source().filePaths(file), new RecordingSink());
      boolean found = false;
      for (LogCapture.Message m : logCapture.getMessages()) {
        if (m.getLevel() == LDLogLevel.INFO && m.getLoggerName().endsWith("FileOverrideSource")) {
          found = true;
        }
      }
      assertTrue(found);
    }
  }
}
