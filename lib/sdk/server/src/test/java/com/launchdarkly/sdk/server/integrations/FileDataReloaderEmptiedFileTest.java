package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogCapture;
import com.launchdarkly.logging.Logs;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.integrations.FileDataReloader.EmptiedFileException;
import com.launchdarkly.sdk.server.integrations.FileDataSourceParsing.FileDataException;
import com.launchdarkly.sdk.server.integrations.OverrideFileLoader.LoadResult;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;
import com.launchdarkly.testhelpers.TempDir;

import org.junit.After;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.launchdarkly.sdk.server.DataModel.FEATURES;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The reloader with the override files loader, on real files that are rewritten in place: a file
 * whose content is in effect and is found empty is deferred until it has stayed empty for the retry
 * delay.
 */
@SuppressWarnings("javadoc")
public class FileDataReloaderEmptiedFileTest {
  // Long enough that a test finishes what it does while a file is empty before the retry reads the
  // file again, also on a loaded machine.
  private static final Duration LONG_RETRY = Duration.ofMillis(1500);
  private static final long WAIT_MILLIS = 10000;
  private static final String REREAD_LOG = "DEBUG:Reading flag data again after finding an empty file";

  private final LogCapture logCapture = Logs.capture();
  private final LDLogger logger = LDLogger.withAdapter(logCapture, "");
  private final List<FileDataReloader> reloaders = new ArrayList<>();

  @After
  public void closeReloaders() {
    for (FileDataReloader r : reloaders) {
      r.close();
    }
  }

  /**
   * Records the value of the flag "flag" in each applied snapshot, or "none" when a snapshot does
   * not have it, and every reported failure.
   */
  private static final class Recorder implements FileDataReloader.Handler {
    final List<String> applied = Collections.synchronizedList(new ArrayList<>());
    final List<FileDataException> errors = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void apply(LoadResult result) {
      String value = "none";
      for (Map.Entry<DataKind, KeyedItems<ItemDescriptor>> kind : result.getData()) {
        if (kind.getKey() != FEATURES) {
          continue;
        }
        for (Map.Entry<String, ItemDescriptor> item : kind.getValue().getItems()) {
          if (item.getKey().equals("flag")) {
            value = LDValue.parse(FEATURES.serialize(item.getValue())).get("variations").get(0).stringValue();
          }
        }
      }
      applied.add(value);
    }

    @Override
    public void onError(FileDataException e) {
      errors.add(e);
    }
  }

  /**
   * The override files loader, counting the loads that have finished.
   */
  private static final class CountingLoader implements FileDataReloader.Loader {
    final OverrideFileLoader loader;
    final AtomicInteger finished = new AtomicInteger();

    CountingLoader(Path... paths) {
      loader = new OverrideFileLoader(Arrays.asList(paths), FileData.DuplicateKeysHandling.FAIL);
    }

    @Override
    public LoadResult load(Duration retryDelay) throws FileDataException, EmptiedFileException {
      try {
        return loader.load(retryDelay);
      } finally {
        finished.incrementAndGet();
      }
    }

    @Override
    public void accepted(LoadResult result) {
      loader.accepted(result);
    }

    void awaitFinished(int expected) throws InterruptedException {
      long deadline = System.currentTimeMillis() + WAIT_MILLIS;
      while (finished.get() < expected) {
        if (System.currentTimeMillis() > deadline) {
          throw new AssertionError("timed out waiting for load " + expected + ", got " + finished.get());
        }
        Thread.sleep(5);
      }
    }
  }

  private FileDataReloader reloader(CountingLoader loader, Recorder recorder, Duration retryDelay) {
    FileDataReloader r = new FileDataReloader(loader, recorder, logger, Duration.ZERO, retryDelay, true);
    reloaders.add(r);
    return r;
  }

  private static void awaitSize(List<?> list, int expected) throws InterruptedException {
    long deadline = System.currentTimeMillis() + WAIT_MILLIS;
    while (list.size() < expected) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for size " + expected + ", got " + list);
      }
      Thread.sleep(5);
    }
  }

  private static Path write(TempDir dir, String name, String contents) throws Exception {
    Path p = dir.getPath().resolve(name);
    Files.write(p, contents.getBytes());
    return p;
  }

  private static String yaml(String value) {
    return "flagValues:\n  flag: \"" + value + "\"\n";
  }

  private static String json(String value) {
    return "{\"flagValues\":{\"flag\":\"" + value + "\"}}";
  }

  private static String readAgainLog(Path file) {
    return "DEBUG:File " + file + " is empty, possibly while it is being rewritten; reading it again before applying it";
  }

  @Test
  public void emptiedYamlFileIsReadAgainAndTheRewrittenContentIsApplied() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", yaml("a"));
      CountingLoader loader = new CountingLoader(file);
      Recorder recorder = new Recorder();
      FileDataReloader r = reloader(loader, recorder, LONG_RETRY);
      r.reloadNow();

      InPlaceWrites.rewrite(file, yaml("b"), () -> {
        // The change signal of the truncation starts a load that finds the file empty.
        r.trigger();
        loader.awaitFinished(2);
      });
      r.trigger();
      awaitSize(recorder.applied, 2);

      assertEquals(Arrays.asList("a", "b"), recorder.applied);
      assertTrue(recorder.errors.isEmpty());
      assertThat(logCapture.getMessageStrings(), hasItem(readAgainLog(file)));
    }
  }

  @Test
  public void yamlFileThatStaysEmptyIsAppliedByTheRetry() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", yaml("a"));
      CountingLoader loader = new CountingLoader(file);
      Recorder recorder = new Recorder();
      FileDataReloader r = reloader(loader, recorder, LONG_RETRY);
      r.reloadNow();

      InPlaceWrites.truncate(file);
      r.trigger();
      loader.awaitFinished(2);
      assertEquals(Collections.singletonList("a"), recorder.applied);

      // No further signal arrives. The retry reads the file again and applies it.
      awaitSize(recorder.applied, 2);
      assertEquals(Arrays.asList("a", "none"), recorder.applied);
      assertEquals(3, loader.finished.get());
      assertTrue(recorder.errors.isEmpty());
      assertThat(logCapture.getMessageStrings(), hasItem(REREAD_LOG));
    }
  }

  @Test
  public void emptiedJsonFileIsReportedOnlyByTheLoadAfterTheOneThatFindsItEmpty() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.json", json("a"));
      CountingLoader loader = new CountingLoader(file);
      Recorder recorder = new Recorder();
      FileDataReloader r = reloader(loader, recorder, LONG_RETRY);
      r.reloadNow();

      InPlaceWrites.truncate(file);
      r.trigger();
      loader.awaitFinished(2);
      assertTrue(recorder.errors.isEmpty());

      awaitSize(recorder.errors, 1);
      assertThat(FileDataReloader.describe(recorder.errors.get(0)),
          containsString("the file has no content, which is not a JSON document"));
      assertEquals(Collections.singletonList("a"), recorder.applied);
    }
  }

  @Test
  public void furtherChangeSignalWithinTheGraceDoesNotApplyTheEmptyFile() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", yaml("a"));
      CountingLoader loader = new CountingLoader(file);
      Recorder recorder = new Recorder();
      FileDataReloader r = reloader(loader, recorder, LONG_RETRY);
      r.reloadNow();

      InPlaceWrites.truncate(file);
      r.trigger();
      loader.awaitFinished(2);
      r.trigger();
      loader.awaitFinished(3);
      assertEquals(Collections.singletonList("a"), recorder.applied);

      // The retry armed by the second load finds that the file has stayed empty for the retry delay.
      awaitSize(recorder.applied, 2);
      assertEquals(Arrays.asList("a", "none"), recorder.applied);
      assertEquals(4, loader.finished.get());
      assertThat(logCapture.getMessageStrings(), hasItem(REREAD_LOG));
    }
  }

  @Test
  public void secondRewriteWithinOneDebounceWindowIsDeferredToo() throws Exception {
    // F1: the write of b and the next truncation happen before any load reads b. The next load
    // finds the file empty again; the file is in a new rewrite, not one that stayed empty.
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", yaml("a"));
      CountingLoader loader = new CountingLoader(file);
      Recorder recorder = new Recorder();
      FileDataReloader r = reloader(loader, recorder, LONG_RETRY);
      r.reloadNow();

      InPlaceWrites.rewrite(file, yaml("b"), () -> {
        r.trigger();
        loader.awaitFinished(2);
      });
      Thread.sleep(50);
      InPlaceWrites.rewrite(file, yaml("c"), () -> {
        r.trigger();
        loader.awaitFinished(3);
      });
      r.trigger();
      awaitSize(recorder.applied, 2);

      assertEquals(Arrays.asList("a", "c"), recorder.applied);
    }
  }

  @Test
  public void changeToAnotherFileWhileAFileIsEmptyDoesNotApplyTheEmptyFile() throws Exception {
    // F2: a change to b starts a load while a is still in the middle of its rewrite.
    try (TempDir dir = TempDir.create()) {
      Path a = write(dir, "a.yaml", yaml("a"));
      Path b = write(dir, "b.yaml", "flagValues:\n  other: \"b1\"\n");
      CountingLoader loader = new CountingLoader(a, b);
      Recorder recorder = new Recorder();
      FileDataReloader r = reloader(loader, recorder, LONG_RETRY);
      r.reloadNow();

      InPlaceWrites.rewrite(a, yaml("a2"), () -> {
        r.trigger();
        loader.awaitFinished(2);
        InPlaceWrites.rewrite(b, "flagValues:\n  other: \"b2\"\n", () -> { });
        r.trigger();
        loader.awaitFinished(3);
      });
      r.trigger();
      awaitSize(recorder.applied, 2);

      assertEquals(Arrays.asList("a", "a2"), recorder.applied);
    }
  }

  @Test
  public void retryThatFindsANewTruncationIsDeferredToo() throws Exception {
    // F3: the file is rewritten, and rewritten again before any load reads the first new content.
    // The retry armed by the first deferral finds the second truncation.
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", yaml("a"));
      CountingLoader loader = new CountingLoader(file);
      Recorder recorder = new Recorder();
      FileDataReloader r = reloader(loader, recorder, LONG_RETRY);
      r.reloadNow();

      InPlaceWrites.rewrite(file, yaml("b"), () -> {
        r.trigger();
        loader.awaitFinished(2);
      });
      Thread.sleep(50);
      InPlaceWrites.rewrite(file, yaml("c"), () -> loader.awaitFinished(3));
      r.trigger();
      awaitSize(recorder.applied, 2);

      assertEquals(Arrays.asList("a", "c"), recorder.applied);
      assertThat(logCapture.getMessageStrings(), hasItem(REREAD_LOG));
    }
  }

  @Test
  public void filesEmptiedTogetherAreAppliedByTheFirstRetry() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path a = write(dir, "a.yaml", yaml("a"));
      Path b = write(dir, "b.yaml", "flagValues:\n  other: \"b\"\n");
      CountingLoader loader = new CountingLoader(a, b);
      Recorder recorder = new Recorder();
      FileDataReloader r = reloader(loader, recorder, LONG_RETRY);
      r.reloadNow();

      InPlaceWrites.truncate(a);
      InPlaceWrites.truncate(b);
      r.trigger();
      loader.awaitFinished(2);
      awaitSize(recorder.applied, 2);

      assertEquals(Arrays.asList("a", "none"), recorder.applied);
      assertEquals(3, loader.finished.get());
      assertThat(logCapture.getMessageStrings(), hasItem(readAgainLog(a)));
      assertThat(logCapture.getMessageStrings(), hasItem(readAgainLog(b)));
    }
  }

  @Test
  public void fileMovedToAnotherConfiguredPathIsDeferredWhenItIsRewritten() throws Exception {
    // Moving a file from one configured path to another leaves the same content in effect, so the
    // load is skipped as unchanged. It must still record that the content now comes from the new
    // path, or an in-place rewrite of that path would be applied while it is empty.
    try (TempDir dir = TempDir.create()) {
      Path a = write(dir, "a.yaml", yaml("a"));
      Path b = dir.getPath().resolve("b.yaml");
      CountingLoader loader = new CountingLoader(a, b);
      Recorder recorder = new Recorder();
      FileDataReloader r = reloader(loader, recorder, LONG_RETRY);
      r.reloadNow();

      Files.move(a, b);
      r.trigger();
      loader.awaitFinished(2);
      assertEquals(Collections.singletonList("a"), recorder.applied);

      InPlaceWrites.rewrite(b, yaml("b"), () -> {
        r.trigger();
        loader.awaitFinished(3);
      });
      r.trigger();
      awaitSize(recorder.applied, 2);

      assertEquals(Arrays.asList("a", "b"), recorder.applied);
      assertThat(logCapture.getMessageStrings(), hasItem(readAgainLog(b)));
    }
  }

  @Test
  public void emptyPlaceholderFileDoesNotDelayAChangeToAnotherFile() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path placeholder = write(dir, "placeholder.yaml", "");
      Path data = write(dir, "data.json", json("a"));
      CountingLoader loader = new CountingLoader(placeholder, data);
      Recorder recorder = new Recorder();
      FileDataReloader r = reloader(loader, recorder, LONG_RETRY);
      r.reloadNow();

      InPlaceWrites.rewrite(data, json("b"), () -> { });
      r.trigger();
      awaitSize(recorder.applied, 2);

      // The load of the signal applied the change, without waiting for a retry.
      assertEquals(Arrays.asList("a", "b"), recorder.applied);
      assertEquals(2, loader.finished.get());
      assertThat(logCapture.getMessageStrings(), not(hasItem(readAgainLog(placeholder))));
    }
  }

  @Test
  public void fileThatIsDeletedAndCreatedEmptyIsAppliedRightAway() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", yaml("a"));
      CountingLoader loader = new CountingLoader(file);
      Recorder recorder = new Recorder();
      FileDataReloader r = reloader(loader, recorder, LONG_RETRY);
      r.reloadNow();

      Files.delete(file);
      r.trigger();
      loader.awaitFinished(2);
      Files.write(file, new byte[0]);
      r.trigger();
      awaitSize(recorder.applied, 3);

      assertEquals(Arrays.asList("a", "none", "none"), recorder.applied);
      assertEquals(3, loader.finished.get());
      assertThat(logCapture.getMessageStrings(), not(hasItem(readAgainLog(file))));
    }
  }

  @Test
  public void retryLeftOverFromAFailureThatFindsTheFileEmptiedReadsItAgain() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", yaml("a"));
      CountingLoader loader = new CountingLoader(file);
      Recorder recorder = new Recorder();
      FileDataReloader r = reloader(loader, recorder, LONG_RETRY);
      r.reloadNow();

      // A load fails and arms the retry.
      InPlaceWrites.rewrite(file, "flagValues: [", () -> { });
      r.trigger();
      awaitSize(recorder.errors, 1);

      // The file is rewritten again, and only the retry reads it while it is empty.
      InPlaceWrites.rewrite(file, yaml("b"), () -> loader.awaitFinished(3));
      r.trigger();
      awaitSize(recorder.applied, 2);

      assertEquals(Arrays.asList("a", "b"), recorder.applied);
      assertThat(logCapture.getMessageStrings(), hasItem(readAgainLog(file)));
    }
  }

  @Test
  public void withoutRetriesAnEmptiedFileIsAppliedRightAway() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", yaml("a"));
      CountingLoader loader = new CountingLoader(file);
      Recorder recorder = new Recorder();
      FileDataReloader r = reloader(loader, recorder, Duration.ZERO);
      r.reloadNow();

      InPlaceWrites.truncate(file);
      r.trigger();
      awaitSize(recorder.applied, 2);

      assertEquals(Arrays.asList("a", "none"), recorder.applied);
      assertEquals(2, loader.finished.get());
      assertThat(logCapture.getMessageStrings(), not(hasItem(readAgainLog(file))));
    }
  }
}
