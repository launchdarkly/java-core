package com.launchdarkly.testhelpers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Provides a temporary file for use by a test. The {@link #close} method deletes
 * the directory, so a try-with-resources block will ensure that it is cleaned up.
 * 
 * <pre><code>
 *     try (TempFile f = TempFile.create(".txt") {
 *         f.setContents("test data");
 *     }
 * </code></pre>
 * 
 * All IOExceptions are rethrown as RuntimeExceptions so that the test code does
 * not need to catch or declare them.
 * 
 * @see TempDir
 * @since 1.1.0
 */
public final class TempFile implements AutoCloseable {
  private final Path path;
  
  TempFile(Path path) {
    this.path = path;
  }

  /**
   * Creates a temporary file in the default directory for temporary files.
   * 
   * @return a file object
   * @see TempDir#tempFile(String)
   */
  public static TempFile create() {
    return create("");
  }

  /**
   * Creates a temporary file in the default directory for temporary files.
   * 
   * @param suffix optional filename suffix, may be empty
   * @return a file object
   * @see TempDir#tempFile(String)
   */
  public static TempFile create(String suffix) {
    try {
      return new TempFile(Files.createTempFile("", suffix));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void close() {
    if (Files.exists(path)) {
      delete();
    }
  }
  
  /**
   * Returns the file path.
   * 
   * @return the file path
   */
  public Path getPath() {
    return path;
  }
  
  /**
   * Deletes the file.
   */
  public void delete() {
    try {
      Files.delete(path);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
  
  /**
   * Replaces the file's contents with the specified data (in UTF-8 encoding).
   * 
   * @param content the new content
   */
  public void setContents(String content) {
    try {
      Files.write(path, content.getBytes("UTF-8"));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
