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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

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
  private static final String REREAD_LOG = "DEBUG:Reading flag data again after a file was found empty";

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
            LDValue v = LDValue.parse(FEATURES.serialize(item.getValue())).get("variations").get(0);
            value = v.isString() ? v.stringValue() : v.toJsonString();
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

    CountingLoader(LongSupplier clock, Path... paths) {
      loader = new OverrideFileLoader(Arrays.asList(paths), FileData.DuplicateKeysHandling.FAIL, clock);
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

  // For the tests that move a clock themselves: a retry delay so long that no retry fires during the
  // test, so every load is one the test starts with reloadNow.
  private static final Duration HOUR = Duration.ofHours(1);

  private static final long TENTH = HOUR.toNanos() / 10;

  // Writes the file again, in place, with a later modification time than the previous write. File
  // system timestamps can be coarse, so it waits until the time differs.
  private static void writeAgain(Path file, String contents) throws Exception {
    long before = Files.getLastModifiedTime(file).toMillis();
    long deadline = System.currentTimeMillis() + 5000;
    do {
      Thread.sleep(20);
      InPlaceWrites.rewrite(file, "x", () -> { });
      InPlaceWrites.rewrite(file, contents, () -> { });
    } while (Files.getLastModifiedTime(file).toMillis() == before && System.currentTimeMillis() < deadline);
  }

  @Test
  public void holdAsksForTheRetryWhenTheDelayIsOver() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", yaml("a"));
      AtomicLong clock = new AtomicLong();
      FileDataReloader r = reloader(new CountingLoader(clock::get, file), new Recorder(), HOUR);
      r.reloadNow();

      InPlaceWrites.truncate(file);
      r.reloadNow();
      assertEquals(HOUR.toNanos() + TENTH, r.getLastRequestedRetryNanos());

      // A later load asks for the rest of the delay, not a whole one.
      clock.addAndGet(Duration.ofMinutes(30).toNanos());
      r.reloadNow();
      assertEquals(Duration.ofMinutes(30).toNanos() + TENTH, r.getLastRequestedRetryNanos());
    }
  }

  @Test
  public void holdAsksForTheRetryWhenTheBoundIsOver() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", yaml("a"));
      AtomicLong clock = new AtomicLong();
      Recorder recorder = new Recorder();
      FileDataReloader r = reloader(new CountingLoader(clock::get, file), recorder, HOUR);
      r.reloadNow();

      InPlaceWrites.truncate(file);
      r.reloadNow();
      for (int i = 0; i < 4; i++) {
        clock.addAndGet(Duration.ofMinutes(60).toNanos());
        writeAgain(file, i % 2 == 0 ? " " : "  ");
        r.reloadNow();
      }
      clock.addAndGet(Duration.ofMinutes(48).toNanos());
      writeAgain(file, "   ");
      r.reloadNow();

      // The file's delay would be over in an hour, but its bound is over in 12 minutes.
      assertEquals(Collections.singletonList("a"), recorder.applied);
      assertEquals(Duration.ofMinutes(12).toNanos() + TENTH, r.getLastRequestedRetryNanos());
    }
  }

  @Test
  public void holdOfSeveralFilesAsksForTheRetryWhenTheFirstIsDue() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path a = write(dir, "a.yaml", yaml("a"));
      Path b = write(dir, "b.yaml", "flagValues:\n  other: \"b\"\n");
      AtomicLong clock = new AtomicLong();
      FileDataReloader r = reloader(new CountingLoader(clock::get, a, b), new Recorder(), HOUR);
      r.reloadNow();

      InPlaceWrites.truncate(a);
      r.reloadNow();
      clock.addAndGet(Duration.ofMinutes(20).toNanos());
      InPlaceWrites.truncate(b);
      r.reloadNow();
      assertEquals(Duration.ofMinutes(40).toNanos() + TENTH, r.getLastRequestedRetryNanos());
    }
  }

  @Test
  public void holdStartedByAChangeSignalAsksForTheRetryWhenTheDelayIsOver() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", yaml("a"));
      AtomicLong clock = new AtomicLong();
      CountingLoader loader = new CountingLoader(clock::get, file);
      FileDataReloader r = reloader(loader, new Recorder(), HOUR);
      r.reloadNow();

      InPlaceWrites.truncate(file);
      r.trigger();
      loader.awaitFinished(2);
      long deadline = System.currentTimeMillis() + WAIT_MILLIS;
      while (r.getLastRequestedRetryNanos() != HOUR.toNanos() + TENTH && System.currentTimeMillis() < deadline) {
        Thread.sleep(5);
      }
      assertEquals(HOUR.toNanos() + TENTH, r.getLastRequestedRetryNanos());
      assertThat(logCapture.getMessageStrings(), hasItem(readAgainLog(file)));
    }
  }

  @Test
  public void failedLoadAsksForTheRetryDelay() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", yaml("a"));
      AtomicLong clock = new AtomicLong();
      Recorder recorder = new Recorder();
      FileDataReloader r = reloader(new CountingLoader(clock::get, file), recorder, HOUR);
      r.reloadNow();

      InPlaceWrites.rewrite(file, "flagValues: [", () -> { });
      r.reloadNow();
      assertEquals(1, recorder.errors.size());
      assertEquals(HOUR.toNanos(), r.getLastRequestedRetryNanos());
    }
  }

  @Test
  public void holdEndsExactlyAfterTheRetryDelay() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", yaml("a"));
      AtomicLong clock = new AtomicLong();
      CountingLoader loader = new CountingLoader(clock::get, file);
      Recorder recorder = new Recorder();
      FileDataReloader r = reloader(loader, recorder, HOUR);
      r.reloadNow();

      InPlaceWrites.truncate(file);
      r.reloadNow();
      clock.addAndGet(HOUR.toNanos() * 95 / 100);
      r.reloadNow();
      assertEquals(Collections.singletonList("a"), recorder.applied);
      clock.addAndGet(HOUR.toNanos() * 5 / 100);
      r.reloadNow();
      assertEquals(Arrays.asList("a", "none"), recorder.applied);
      assertTrue(recorder.errors.isEmpty());
    }
  }

  @Test
  public void fileRewrittenWithTheSameContentIsHeldBackAgainLater() throws Exception {
    // A tool rewrites the file in place now and then with the same content. The load after a rewrite
    // is skipped as unchanged, and that must start the file's bound over as an application does, or
    // a rewrite five retry delays later would be applied while the file is empty.
    try (TempDir dir = TempDir.create()) {
      Path file = write(dir, "overrides.yaml", yaml("a"));
      AtomicLong clock = new AtomicLong();
      CountingLoader loader = new CountingLoader(clock::get, file);
      Recorder recorder = new Recorder();
      FileDataReloader r = reloader(loader, recorder, HOUR);
      r.reloadNow();

      InPlaceWrites.truncate(file);
      r.reloadNow();
      InPlaceWrites.rewrite(file, yaml("a"), () -> { });
      r.reloadNow();
      assertEquals(3, loader.finished.get());
      assertEquals(Collections.singletonList("a"), recorder.applied);

      clock.addAndGet(HOUR.toNanos() * 6);
      InPlaceWrites.truncate(file);
      r.reloadNow();
      assertEquals(Collections.singletonList("a"), recorder.applied);
      assertTrue(recorder.errors.isEmpty());
    }
  }

  @Test
  public void sameBytesMovedToAFileOfAnotherFormatAreApplied() throws Exception {
    // The format depends on the path, so the load after the move is not skipped as unchanged: YAML
    // reads on as true, and JSON reads it as a string.
    try (TempDir dir = TempDir.create()) {
      Path yaml = write(dir, "overrides.yaml", "{\"flagValues\": {\"flag\": on}}");
      Path json = dir.getPath().resolve("overrides.json");
      Recorder recorder = new Recorder();
      FileDataReloader r = reloader(new CountingLoader(yaml, json), recorder, HOUR);
      r.reloadNow();
      Files.move(yaml, json);
      r.reloadNow();
      assertEquals(Arrays.asList("true", "on"), recorder.applied);
    }
  }

  @Test
  public void changesToOtherFilesWaitWhileAFileIsHeld() throws Exception {
    // A reload applies all the files or none.
    try (TempDir dir = TempDir.create()) {
      Path emptied = write(dir, "emptied.yaml", "flagValues:\n  other: \"x\"\n");
      Path changed = write(dir, "changed.yaml", yaml("b1"));
      AtomicLong clock = new AtomicLong();
      CountingLoader loader = new CountingLoader(clock::get, emptied, changed);
      Recorder recorder = new Recorder();
      FileDataReloader r = reloader(loader, recorder, HOUR);
      r.reloadNow();

      InPlaceWrites.truncate(emptied);
      InPlaceWrites.rewrite(changed, yaml("b2"), () -> { });
      r.reloadNow();
      assertEquals(Collections.singletonList("b1"), recorder.applied);
      clock.addAndGet(HOUR.toNanos());
      r.reloadNow();
      assertEquals(Arrays.asList("b1", "b2"), recorder.applied);
    }
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
    // Moving a file from one configured path to another leaves the same flags in effect. The load
    // after the move records that the content now comes from the new path, or an in-place rewrite
    // of that path would be applied while it is empty. The skip-unchanged digest covers the paths,
    // so that load is applied rather than skipped.
    try (TempDir dir = TempDir.create()) {
      Path a = write(dir, "a.yaml", yaml("a"));
      Path b = dir.getPath().resolve("b.yaml");
      CountingLoader loader = new CountingLoader(a, b);
      Recorder recorder = new Recorder();
      FileDataReloader r = reloader(loader, recorder, LONG_RETRY);
      r.reloadNow();

      Files.move(a, b);
      r.trigger();
      awaitSize(recorder.applied, 2);
      assertEquals(Arrays.asList("a", "a"), recorder.applied);

      InPlaceWrites.rewrite(b, yaml("b"), () -> {
        r.trigger();
        loader.awaitFinished(3);
      });
      r.trigger();
      awaitSize(recorder.applied, 3);

      assertEquals(Arrays.asList("a", "a", "b"), recorder.applied);
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
