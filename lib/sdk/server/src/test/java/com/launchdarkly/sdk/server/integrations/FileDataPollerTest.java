package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.testhelpers.TempDir;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("javadoc")
public class FileDataPollerTest {
  private static final Duration INTERVAL = Duration.ofMillis(20);
  private static final long WAIT_MILLIS = 5000;
  private static final long QUIET_MILLIS = 250;

  private static void awaitCount(AtomicInteger counter, int expected) throws InterruptedException {
    long deadline = System.currentTimeMillis() + WAIT_MILLIS;
    while (counter.get() < expected) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for " + expected + " change signals, got " + counter.get());
      }
      Thread.sleep(5);
    }
  }

  private static Path createFile(TempDir dir, String name, String contents, long modifiedMillis) throws Exception {
    Path path = dir.getPath().resolve(name);
    Files.write(path, contents.getBytes());
    Files.setLastModifiedTime(path, FileTime.fromMillis(modifiedMillis));
    return path;
  }

  private static void rewrite(Path path, String contents, long modifiedMillis) throws Exception {
    Files.write(path, contents.getBytes());
    Files.setLastModifiedTime(path, FileTime.fromMillis(modifiedMillis));
  }

  @Test
  public void detectsModifiedTimeChange() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = createFile(dir, "a.json", "{}", 1000_000);
      AtomicInteger changes = new AtomicInteger();
      try (FileDataPoller poller = new FileDataPoller(Collections.singletonList(file), INTERVAL, changes::incrementAndGet)) {
        Thread.sleep(QUIET_MILLIS);
        assertEquals(0, changes.get());

        rewrite(file, "{}", 2000_000); // same size, new modified time
        awaitCount(changes, 1);
      }
    }
  }

  @Test
  public void detectsSizeChangeWithSameModifiedTime() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = createFile(dir, "a.json", "{}", 1000_000);
      AtomicInteger changes = new AtomicInteger();
      try (FileDataPoller poller = new FileDataPoller(Collections.singletonList(file), INTERVAL, changes::incrementAndGet)) {
        rewrite(file, "{\"flagValues\":{}}", 1000_000);
        awaitCount(changes, 1);
      }
    }
  }

  @Test
  public void firesOncePerChange() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = createFile(dir, "a.json", "{}", 1000_000);
      AtomicInteger changes = new AtomicInteger();
      try (FileDataPoller poller = new FileDataPoller(Collections.singletonList(file), INTERVAL, changes::incrementAndGet)) {
        rewrite(file, "{}", 2000_000);
        awaitCount(changes, 1);
        Thread.sleep(QUIET_MILLIS);
        assertEquals(1, changes.get());
      }
    }
  }

  @Test
  public void detectsFileAppearing() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = dir.getPath().resolve("later.json");
      AtomicInteger changes = new AtomicInteger();
      try (FileDataPoller poller = new FileDataPoller(Collections.singletonList(file), INTERVAL, changes::incrementAndGet)) {
        Thread.sleep(QUIET_MILLIS);
        assertEquals(0, changes.get());

        Files.write(file, "{}".getBytes());
        awaitCount(changes, 1);
      }
    }
  }

  @Test
  public void detectsFileDisappearing() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = createFile(dir, "a.json", "{}", 1000_000);
      AtomicInteger changes = new AtomicInteger();
      try (FileDataPoller poller = new FileDataPoller(Collections.singletonList(file), INTERVAL, changes::incrementAndGet)) {
        Files.delete(file);
        awaitCount(changes, 1);
      }
    }
  }

  @Test
  public void watchesEveryConfiguredFile() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path first = createFile(dir, "a.json", "{}", 1000_000);
      Path second = createFile(dir, "b.json", "{}", 1000_000);
      List<Path> paths = Arrays.asList(first, second);
      AtomicInteger changes = new AtomicInteger();
      try (FileDataPoller poller = new FileDataPoller(paths, INTERVAL, changes::incrementAndGet)) {
        rewrite(second, "{}", 2000_000);
        awaitCount(changes, 1);
        rewrite(first, "{}", 2000_000);
        awaitCount(changes, 2);
      }
    }
  }

  @Test
  public void stopsOnClose() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = createFile(dir, "a.json", "{}", 1000_000);
      AtomicInteger changes = new AtomicInteger();
      FileDataPoller poller = new FileDataPoller(Collections.singletonList(file), INTERVAL, changes::incrementAndGet);
      poller.close();
      poller.close(); // idempotent

      rewrite(file, "{}", 2000_000);
      Thread.sleep(QUIET_MILLIS);
      assertEquals(0, changes.get());
    }
  }

  @Test
  public void closeReturnsWhileCallbackBlocks() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path file = createFile(dir, "a.json", "{}", 1000_000);
      CountDownLatch entered = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      FileDataPoller poller = new FileDataPoller(Collections.singletonList(file), INTERVAL, () -> {
        entered.countDown();
        try {
          release.await(WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      rewrite(file, "{}", 2000_000);
      assertTrue(entered.await(WAIT_MILLIS, TimeUnit.MILLISECONDS));

      long start = System.currentTimeMillis();
      poller.close();
      assertThat(System.currentTimeMillis() - start, lessThan(1000L));
      release.countDown();
    }
  }

  @Test
  public void unreadableAttributesCountAsAbsent() throws Exception {
    try (TempDir dir = TempDir.create()) {
      Path missing = dir.getPath().resolve("nope").resolve("missing.json");
      List<FileDataPoller.FileState> states = FileDataPoller.observeAll(Collections.singletonList(missing));
      assertEquals(FileDataPoller.FileState.ABSENT, states.get(0));
    }
  }
}
