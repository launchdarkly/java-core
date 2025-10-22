package com.launchdarkly.testhelpers;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Provides a temporary directory for use by a test. The {@link #close} method deletes
 * the directory, so a try-with-resources block will ensure that it is cleaned up.
 * <p>
 * All methods that could cause an IOException will throw it as a RuntimeException
 * instead, so tests do not need to catch IOException.
 * 
 * <pre><code>
 *     try (TempDir dir = TempDir.create()) {
 *         TempFile f = dir.tempFile(".txt");
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
public final class TempDir implements AutoCloseable {
  private final Path path;
  
  private TempDir(Path path) {
    this.path = path;
  }
  
  /**
   * Creates a temporary directory.
   * 
   * @return a directory object
   */
  public static TempDir create() {
    try {
      return new TempDir(Files.createTempDirectory("java-sdk-tests"));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Returns the directory path.
   * 
   * @return a path
   */
  public Path getPath() {
    return path;
  }
  
  /**
   * Calls {@link #delete()} if the directory still exists.
   */
  @Override
  public void close() {
    if (Files.exists(path)) {
      delete();
    }
  }

  /**
   * Deletes the directory and all its contents.
   */
  public void delete() {
    try {
      Files.walkFileTree(path, 
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
            Files.delete(dir);
            return FileVisitResult.CONTINUE;
          }
          
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }
      });
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
  
  /**
   * Creates a temporary file within the directory.
   * 
   * @return a file object
   */
  public TempFile tempFile() {
    return tempFile("");
  }
  
  /**
   * Creates a temporary file within the directory.
   * 
   * @param suffix optional filename suffix, may be empty
   * @return a file object
   */
  public TempFile tempFile(String suffix) {
    try {
      return new TempFile(Files.createTempFile(path, "java-sdk-tests", suffix));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}