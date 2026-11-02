package com.launchdarkly.sdk.server.integrations;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Rewrites files in place the way many applications and editors save them: the file is truncated
 * when it is opened for writing, and the new content is written through the same open stream. A
 * reader in between finds the file empty. The tests use these writes, not an atomic replacement, so
 * that the code under test must handle a file that is being rewritten.
 */
@SuppressWarnings("javadoc")
final class InPlaceWrites {
  /**
   * Work done while the file is empty, between the truncation and the write.
   */
  interface WhileEmpty {
    void run() throws Exception;
  }

  private InPlaceWrites() {}

  /**
   * Truncates the file, pauses, and then writes the new content.
   */
  static void rewrite(Path path, String content, Duration pause) throws Exception {
    rewrite(path, content, () -> Thread.sleep(pause.toMillis()));
  }

  /**
   * Truncates the file, runs the given work while the file is empty, and then writes the new content.
   */
  static void rewrite(Path path, String content, WhileEmpty whileEmpty) throws Exception {
    try (OutputStream out = Files.newOutputStream(path)) {
      whileEmpty.run();
      out.write(content.getBytes(StandardCharsets.UTF_8));
    }
  }

  /**
   * Truncates the file and leaves it empty.
   */
  static void truncate(Path path) throws Exception {
    rewrite(path, "", () -> { });
  }
}
