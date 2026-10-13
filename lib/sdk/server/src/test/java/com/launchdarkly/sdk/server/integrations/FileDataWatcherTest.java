package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.logging.LDLogLevel;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogCapture;
import com.launchdarkly.logging.Logs;
import com.launchdarkly.testhelpers.TempDir;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("javadoc")
public class FileDataWatcherTest {
  private static final LDLogger testLogger = LDLogger.none();
  private static final long WAIT_MILLIS = 10000;
  private static final long QUIET_MILLIS = 300;
  private static final Duration DIRECTORY_RETRY = Duration.ofMillis(50);

  private static void awaitAtLeast(AtomicInteger counter, int expected) throws InterruptedException {
    long deadline = System.currentTimeMillis() + WAIT_MILLIS;
    while (counter.get() < expected) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for " + expected + " change signals, got " + counter.get());
      }
      Thread.sleep(10);
    }
  }

  @Test
  public void startSignalsOnceSoThatAChangeBeforeWatchingIsNotMissed() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = dir.getPath().resolve("a.json");
      Files.write(file, "{}".getBytes());
      AtomicInteger changes = new AtomicInteger();
      try (FileDataWatcher watcher = FileDataWatcher.create(Collections.singletonList(file), testLogger)) {
        watcher.start(changes::incrementAndGet);
        awaitAtLeast(changes, 1);
        Thread.sleep(QUIET_MILLIS);
        assertEquals(1, changes.get());
      }
    }
  }

  @Test
  public void modifiedFileSignalsChange() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = dir.getPath().resolve("a.json");
      Files.write(file, "{}".getBytes());
      AtomicInteger changes = new AtomicInteger();
      try (FileDataWatcher watcher = FileDataWatcher.create(Collections.singletonList(file), testLogger)) {
        watcher.start(changes::incrementAndGet);
        awaitAtLeast(changes, 1);
        Thread.sleep(QUIET_MILLIS);

        Files.write(file, "{\"flagValues\":{}}".getBytes());
        awaitAtLeast(changes, 2);
      }
    }
  }

  @Test
  public void fileThatDoesNotExistYetSignalsChangeWhenItAppears() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = dir.getPath().resolve("later.json");
      AtomicInteger changes = new AtomicInteger();
      try (FileDataWatcher watcher = FileDataWatcher.create(Collections.singletonList(file), testLogger)) {
        watcher.start(changes::incrementAndGet);
        awaitAtLeast(changes, 1);
        Thread.sleep(QUIET_MILLIS);

        Files.write(file, "{}".getBytes());
        awaitAtLeast(changes, 2);
      }
    }
  }

  @Test
  public void deletedFileSignalsChange() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = dir.getPath().resolve("a.json");
      Files.write(file, "{}".getBytes());
      AtomicInteger changes = new AtomicInteger();
      try (FileDataWatcher watcher = FileDataWatcher.create(Collections.singletonList(file), testLogger)) {
        watcher.start(changes::incrementAndGet);
        awaitAtLeast(changes, 1);
        Thread.sleep(QUIET_MILLIS);

        Files.delete(file);
        awaitAtLeast(changes, 2);
      }
    }
  }

  @Test
  public void unrelatedFileInSameDirectoryDoesNotSignal() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = dir.getPath().resolve("a.json");
      Files.write(file, "{}".getBytes());
      AtomicInteger changes = new AtomicInteger();
      try (FileDataWatcher watcher = FileDataWatcher.create(Collections.singletonList(file), testLogger)) {
        watcher.start(changes::incrementAndGet);
        awaitAtLeast(changes, 1);
        Thread.sleep(QUIET_MILLIS);

        Files.write(dir.getPath().resolve("other.json"), "{}".getBytes());
        Thread.sleep(QUIET_MILLIS);
        assertEquals(1, changes.get());
      }
    }
  }

  @Test
  public void relativePathIsWatchedByItsAbsoluteLocation() throws Exception {
    // The watcher must compare the directory-relative event name against the same absolute form
    // it registered, whatever form the caller used.
    try (TempDir dir = TempDir.create()) {
      Path file = dir.getPath().resolve("sub").resolve("..").resolve("a.json");
      Files.createDirectories(dir.getPath().resolve("sub"));
      Files.write(file, "{}".getBytes());
      AtomicInteger changes = new AtomicInteger();
      try (FileDataWatcher watcher = FileDataWatcher.create(Collections.singletonList(file), testLogger)) {
        watcher.start(changes::incrementAndGet);
        awaitAtLeast(changes, 1);
        Thread.sleep(QUIET_MILLIS);

        Files.write(file, "{\"flagValues\":{}}".getBytes());
        awaitAtLeast(changes, 2);
      }
    }
  }

  @Test
  public void deletedAndRecreatedDirectoryIsWatchedAgain() throws Exception {
    LogCapture logCapture = Logs.capture();
    LDLogger logger = LDLogger.withAdapter(logCapture, "");
    try (TempDir dir = TempDir.create()) {
      Path sub = dir.getPath().resolve("sub");
      Files.createDirectory(sub);
      Path file = sub.resolve("a.json");
      Files.write(file, "{}".getBytes());
      AtomicInteger changes = new AtomicInteger();
      try (FileDataWatcher watcher = FileDataWatcher.create(Collections.singletonList(file), DIRECTORY_RETRY, logger)) {
        watcher.start(changes::incrementAndGet);
        awaitAtLeast(changes, 1);
        Thread.sleep(QUIET_MILLIS);

        // Delete the file and then its directory, and let the deletion notifications settle.
        Files.delete(file);
        Files.delete(sub);
        awaitAtLeast(changes, 2);
        Thread.sleep(QUIET_MILLIS);
        int afterDeletion = changes.get();

        // A file written into the recreated directory must be reported.
        Files.createDirectory(sub);
        Files.write(file, "{\"flagValues\":{}}".getBytes());
        awaitAtLeast(changes, afterDeletion + 1);

        // The lost directory is reported once, not on every registration attempt.
        long warnings = logCapture.getMessages().stream().filter(m -> m.getLevel() == LDLogLevel.WARN).count();
        assertEquals(1, warnings);
      }
    }
  }

  @Test
  public void directoryMissingAtStartIsWatchedOnceItExists() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path sub = dir.getPath().resolve("later");
      Path file = sub.resolve("a.json");
      AtomicInteger changes = new AtomicInteger();
      try (FileDataWatcher watcher = FileDataWatcher.create(Collections.singletonList(file), DIRECTORY_RETRY, testLogger)) {
        watcher.start(changes::incrementAndGet);
        awaitAtLeast(changes, 1);
        Thread.sleep(QUIET_MILLIS);

        // Create the directory and write the file into it.
        Files.createDirectory(sub);
        Files.write(file, "{}".getBytes());
        // The file is reported even though its directory did not exist when watching began.
        awaitAtLeast(changes, 2);
      }
    }
  }

  @Test
  public void closeEndsTheWorkerWhileADirectoryIsMissing() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = dir.getPath().resolve("later").resolve("a.json");
      AtomicInteger changes = new AtomicInteger();
      FileDataWatcher watcher = FileDataWatcher.create(Collections.singletonList(file), DIRECTORY_RETRY, testLogger);
      watcher.start(changes::incrementAndGet);
      awaitAtLeast(changes, 1);

      // Close while the worker is waiting for its next registration attempt.
      watcher.close();
      // The worker must end instead of continuing to retry.
      assertTrue(watcher.awaitStop(WAIT_MILLIS));
    }
  }

  @Test
  public void closeStopsSignalsAndIsIdempotent() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = dir.getPath().resolve("a.json");
      Files.write(file, "{}".getBytes());
      AtomicInteger changes = new AtomicInteger();
      FileDataWatcher watcher = FileDataWatcher.create(Collections.singletonList(file), testLogger);
      watcher.start(changes::incrementAndGet);
      awaitAtLeast(changes, 1);
      watcher.close();
      watcher.close();

      Files.write(file, "{\"flagValues\":{}}".getBytes());
      Thread.sleep(QUIET_MILLIS);
      assertEquals(1, changes.get());
    }
  }
}
