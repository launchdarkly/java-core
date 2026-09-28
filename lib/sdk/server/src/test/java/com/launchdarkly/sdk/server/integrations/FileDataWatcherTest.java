package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.testhelpers.TempDir;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

@SuppressWarnings("javadoc")
public class FileDataWatcherTest {
  private static final LDLogger testLogger = LDLogger.none();
  private static final long WAIT_MILLIS = 10000;
  private static final long QUIET_MILLIS = 300;

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
  public void missingDirectoryIsAnErrorAtCreation() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = dir.getPath().resolve("nope").resolve("a.json");
      try {
        FileDataWatcher.create(Collections.singletonList(file), testLogger).close();
        fail("expected IOException");
      } catch (IOException e) {
        // expected
      }
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
