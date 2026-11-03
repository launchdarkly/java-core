package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.logging.LDLogLevel;
import com.launchdarkly.logging.LogCapture;
import com.launchdarkly.logging.Logs;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.Components;
import com.launchdarkly.sdk.server.LDClient;
import com.launchdarkly.sdk.server.LDConfig;
import com.launchdarkly.sdk.server.TestComponents;
import com.launchdarkly.sdk.server.integrations.FileOverrides.ChangeDetection;
import com.launchdarkly.sdk.server.subsystems.ClientContext;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.OverrideSource;
import com.launchdarkly.testhelpers.TempDir;

import org.junit.After;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.launchdarkly.sdk.server.DataModel.FEATURES;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The file-based override source while its file is rewritten in place: the file is truncated, stays
 * empty for a while, and then gets the new content. The overrides in effect must go from the old
 * content to the new content without a moment in between without the override.
 */
@SuppressWarnings("javadoc")
public class FileOverrideSourceInPlaceRewriteTest {
  private static final long WAIT_MILLIS = 10000;
  private static final LDContext CONTEXT = LDContext.create("user-key");

  // A watching source reads the file within the debounce delay of the truncation. The retry delay
  // leaves a margin over the pause for a loaded machine.
  private static final Duration WATCHING_PAUSE = Duration.ofMillis(300);
  private static final Duration WATCHING_RETRY_DELAY = Duration.ofMillis(1500);

  // A polling source examines the file once per second. A pause longer than that makes sure that one
  // examination finds the file empty, and the retry delay is longer than the pause by a margin.
  private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
  private static final Duration POLLING_PAUSE = Duration.ofMillis(1200);
  private static final Duration POLLING_RETRY_DELAY = Duration.ofMillis(2500);

  private final LogCapture logCapture = Logs.capture();
  private final ClientContext context = TestComponents.clientContext("sdk-key",
      new LDConfig.Builder().logging(Components.logging(logCapture).level(LDLogLevel.DEBUG)).build());
  private final List<AutoCloseable> closeables = new ArrayList<>();

  @After
  public void close() throws Exception {
    for (AutoCloseable c : closeables) {
      c.close();
    }
  }

  private static String doc(Path file, String value) {
    return file.toString().endsWith(".yaml") ? "flagValues:\n  flag: \"" + value + "\"\n"
        : "{\"flagValues\":{\"flag\":\"" + value + "\"}}";
  }

  private static Path write(TempDir dir, String name, String value) throws Exception {
    Path p = dir.getPath().resolve(name);
    Files.write(p, doc(p, value).getBytes(StandardCharsets.UTF_8));
    return p;
  }

  private static FileOverrideSourceImpl source(Path file, ChangeDetection mode, Duration retryDelay,
      ClientContext context) {
    return new FileOverrideSourceImpl(Collections.singletonList(file.toAbsolutePath()),
        FileData.DuplicateKeysHandling.FAIL, mode, POLL_INTERVAL, retryDelay,
        context.getBaseLogger().subLogger("FileOverrideSource"), FileDataWatcher::create);
  }

  // The value of the override "flag" in a snapshot, or "none" when the snapshot does not have it.
  private static String flagValue(Map<String, Map<String, ItemDescriptor>> snapshot) {
    ItemDescriptor item = snapshot.get(FEATURES.getName()).get("flag");
    if (item == null) {
      return "none";
    }
    return LDValue.parse(FEATURES.serialize(item)).get("variations").get(0).stringValue();
  }

  /**
   * The writes a test makes to the file once the source has started.
   */
  private interface Rewrites {
    void run() throws Exception;
  }

  // Starts the source on a file whose override has the value "a", makes the writes, and returns the
  // value of the override in every snapshot the source supplies until one has the final value, and
  // in any snapshot shortly after that.
  private List<String> appliedThroughRewrites(Path file, OverrideSource source, Rewrites rewrites,
      String finalValue) throws Exception {
    FileOverrideSourceTest.RecordingSink sink = new FileOverrideSourceTest.RecordingSink();
    closeables.add(source);
    source.start(sink);
    List<String> applied = new ArrayList<>();
    applied.add(flagValue(sink.await()));
    // Let the start of change detection settle.
    Thread.sleep(300);

    rewrites.run();

    long deadline = System.currentTimeMillis() + WAIT_MILLIS;
    while (!applied.get(applied.size() - 1).equals(finalValue) && System.currentTimeMillis() < deadline) {
      Map<String, Map<String, ItemDescriptor>> snapshot = sink.snapshots.poll(100, TimeUnit.MILLISECONDS);
      if (snapshot != null) {
        applied.add(flagValue(snapshot));
      }
    }
    // A later snapshot would be wrong too.
    Map<String, Map<String, ItemDescriptor>> late = sink.snapshots.poll(500, TimeUnit.MILLISECONDS);
    if (late != null) {
      applied.add(flagValue(late));
    }
    return applied;
  }

  private void sourceKeepsTheOverrideThroughARewrite(String name, ChangeDetection mode, Duration pause,
      Duration retryDelay) throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, name, "a");
      List<String> applied = appliedThroughRewrites(file, source(file, mode, retryDelay, context),
          () -> InPlaceWrites.rewrite(file, doc(file, "b"), pause), "b");
      assertEquals(Arrays.asList("a", "b"), applied);
    }
  }

  // The override must have a value in every snapshot, and the last one must be the final value. An
  // intermediate value may or may not be applied.
  private static void assertNeverWithoutTheOverride(List<String> applied, String finalValue) {
    assertEquals(applied.toString(), "a", applied.get(0));
    assertEquals(applied.toString(), finalValue, applied.get(applied.size() - 1));
    assertTrue(applied.toString(), !applied.contains("none"));
  }

  @Test
  public void watchingSourceKeepsTheOverrideThroughARewriteOfAJsonFile() throws Exception {
    sourceKeepsTheOverrideThroughARewrite("overrides.json", ChangeDetection.WATCHING, WATCHING_PAUSE,
        WATCHING_RETRY_DELAY);
  }

  @Test
  public void watchingSourceKeepsTheOverrideThroughARewriteOfAYamlFile() throws Exception {
    sourceKeepsTheOverrideThroughARewrite("overrides.yaml", ChangeDetection.WATCHING, WATCHING_PAUSE,
        WATCHING_RETRY_DELAY);
  }

  @Test
  public void pollingSourceKeepsTheOverrideThroughARewriteOfAJsonFile() throws Exception {
    sourceKeepsTheOverrideThroughARewrite("overrides.json", ChangeDetection.POLLING, POLLING_PAUSE,
        POLLING_RETRY_DELAY);
  }

  @Test
  public void pollingSourceKeepsTheOverrideThroughARewriteOfAYamlFile() throws Exception {
    sourceKeepsTheOverrideThroughARewrite("overrides.yaml", ChangeDetection.POLLING, POLLING_PAUSE,
        POLLING_RETRY_DELAY);
  }

  @Test
  public void watchingSourceKeepsTheOverrideThroughTwoQuickRewrites() throws Exception {
    // The write of b and the second truncation fall in one debounce window, so no load reads b.
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", "a");
      List<String> applied = appliedThroughRewrites(file,
          source(file, ChangeDetection.WATCHING, WATCHING_RETRY_DELAY, context), () -> {
            InPlaceWrites.rewrite(file, doc(file, "b"), Duration.ofMillis(150));
            InPlaceWrites.rewrite(file, doc(file, "c"), WATCHING_PAUSE);
          }, "c");
      assertNeverWithoutTheOverride(applied, "c");
    }
  }

  @Test
  public void defaultSourceKeepsTheOverrideThroughRewritesASecondApart() throws Exception {
    // The default source polls once per second and retries after one second. Two rewrites about a
    // second apart can let a poll or a retry find the second truncation.
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", "a");
      List<String> applied = appliedThroughRewrites(file, FileOverrides.source().filePaths(file).build(context),
          () -> {
            InPlaceWrites.rewrite(file, doc(file, "b"), WATCHING_PAUSE);
            Thread.sleep(700);
            InPlaceWrites.rewrite(file, doc(file, "c"), WATCHING_PAUSE);
          }, "c");
      assertNeverWithoutTheOverride(applied, "c");
    }
  }

  private void clientServesTheOverrideThroughARewrite(String name) throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, name, "a");
      TestDataV2 td = TestDataV2.synchronizer();
      td.update(td.flag("flag").valueForAll(LDValue.of("launchdarkly-value")));
      LDConfig config = new LDConfig.Builder()
          .dataSystem(Components.dataSystem().custom().synchronizers(td)
              .overrides(ctx -> source(file, ChangeDetection.WATCHING, WATCHING_RETRY_DELAY, ctx)))
          .events(Components.noEvents())
          .logging(Components.logging(logCapture).level(LDLogLevel.DEBUG))
          .build();
      try (LDClient client = new LDClient("sdk-key", config)) {
        // The distinct values the client serves, in order.
        List<String> served = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean stop = new AtomicBoolean(false);
        Thread sampler = new Thread(() -> {
          while (!stop.get()) {
            String value = client.stringVariation("flag", CONTEXT, "default");
            if (served.isEmpty() || !served.get(served.size() - 1).equals(value)) {
              served.add(value);
            }
            try {
              Thread.sleep(1);
            } catch (InterruptedException e) {
              return;
            }
          }
        });
        sampler.start();
        try {
          Thread.sleep(300);
          InPlaceWrites.rewrite(file, doc(file, "b"), WATCHING_PAUSE);
          long deadline = System.currentTimeMillis() + WAIT_MILLIS;
          while (!served.contains("b") && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
          }
          Thread.sleep(500);
        } finally {
          stop.set(true);
          sampler.join();
        }
        assertEquals(Arrays.asList("a", "b"), served);
      }
    }
  }

  @Test
  public void clientServesTheOverrideThroughARewriteOfAJsonFile() throws Exception {
    clientServesTheOverrideThroughARewrite("overrides.json");
  }

  @Test
  public void clientServesTheOverrideThroughARewriteOfAYamlFile() throws Exception {
    clientServesTheOverrideThroughARewrite("overrides.yaml");
  }
}
