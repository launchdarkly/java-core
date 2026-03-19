package com.launchdarkly.sdk.server.integrations;

import com.google.common.io.ByteStreams;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.server.LDConfig.Builder;
import com.launchdarkly.sdk.server.datasources.Initializer;
import com.launchdarkly.sdk.server.datasources.Synchronizer;
import com.launchdarkly.sdk.server.subsystems.ClientContext;
import com.launchdarkly.sdk.server.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.server.subsystems.DataSource;
import com.launchdarkly.sdk.server.subsystems.DataSourceBuildInputs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * To use the file data source, obtain a new instance of this class with {@link FileData#dataSource()};
 * call the builder method {@link #filePaths(String...)} to specify file path(s), and/or
 * {@link #classpathResources(String...)} to specify classpath data resources; then pass the resulting
 * object to {@link Builder#dataSource(ComponentConfigurer)}.
 * <p>
 * For more details, see {@link FileData}.
 * 
 * @since 4.12.0
 */
public final class FileDataSourceBuilder implements ComponentConfigurer<DataSource> {
  final List<SourceInfo> sources = new ArrayList<>(); // visible for tests
  private boolean autoUpdate = false;
  private FileData.DuplicateKeysHandling duplicateKeysHandling = FileData.DuplicateKeysHandling.FAIL;

  private boolean shouldPersist = true;
  
  /**
   * Adds any number of source files for loading flag data, specifying each file path as a string. The files will
   * not actually be loaded until the LaunchDarkly client starts up.
   * <p> 
   * Files will be parsed as JSON if their first non-whitespace character is '{'. Otherwise, they will be parsed as YAML.
   *
   * @param filePaths path(s) to the source file(s); may be absolute or relative to the current working directory
   * @return the same factory object
   * 
   * @throws InvalidPathException if one of the parameters is not a valid file path
   */
  public FileDataSourceBuilder filePaths(String... filePaths) throws InvalidPathException {
    for (String p: filePaths) {
      sources.add(new FilePathSourceInfo(Paths.get(p)));
    }
    return this;
  }

  /**
   * Adds any number of source files for loading flag data, specifying each file path as a Path. The files will
   * not actually be loaded until the LaunchDarkly client starts up.
   * <p> 
   * Files will be parsed as JSON if their first non-whitespace character is '{'. Otherwise, they will be parsed as YAML.
   * 
   * @param filePaths path(s) to the source file(s); may be absolute or relative to the current working directory
   * @return the same factory object
   */
  public FileDataSourceBuilder filePaths(Path... filePaths) {
    for (Path p: filePaths) {
      sources.add(new FilePathSourceInfo(p));
    }
    return this;
  }

  /**
   * Adds any number of classpath resources for loading flag data. The resources will not actually be loaded until the
   * LaunchDarkly client starts up.
   * <p> 
   * Files will be parsed as JSON if their first non-whitespace character is '{'. Otherwise, they will be parsed as YAML.
   * 
   * @param resourceLocations resource location(s) in the format used by {@code ClassLoader.getResource()}; these
   *   are absolute paths, so for instance a resource called "data.json" in the package "com.mypackage" would have
   *   the location "/com/mypackage/data.json" 
   * @return the same factory object
   */
  public FileDataSourceBuilder classpathResources(String... resourceLocations) {
    for (String location: resourceLocations) {
      sources.add(new ClasspathResourceSourceInfo(location));
    }
    return this;
  }
  
  /**
   * Specifies whether the data source should watch for changes to the source file(s) and reload flags
   * whenever there is a change. By default, it will not, so the flags will only be loaded once. This feature
   * only works with real files, not with {@link #classpathResources(String...)}.
   * <p>
   * Note that auto-updating will only work if all of the files you specified have valid directory paths at
   * startup time; if a directory does not exist, creating it later will not result in files being loaded from it.
   * <p>
   * The performance of this feature depends on what implementation of {@code java.nio.file.WatchService} is
   * available in the Java runtime. On Linux and Windows, an implementation based on native filesystem APIs
   * should be available. On MacOS, there is a long-standing known issue where due to the lack of such an
   * implementation, it must use a file polling approach that can take up to 10 seconds to detect a change. 
   * 
   * @param autoUpdate true if flags should be reloaded whenever a source file changes
   * @return the same factory object
   */
  public FileDataSourceBuilder autoUpdate(boolean autoUpdate) {
    this.autoUpdate = autoUpdate;
    return this;
  }

  /**
   * Specifies how to handle keys that are duplicated across files.
   * <p>
   * By default, data loading will fail if keys are duplicated across files ({@link FileData.DuplicateKeysHandling#FAIL}).
   * 
   * @param duplicateKeysHandling specifies how to handle duplicate keys
   * @return the same factory object
   * @since 5.3.0
   */
  public FileDataSourceBuilder duplicateKeysHandling(FileData.DuplicateKeysHandling duplicateKeysHandling) {
    this.duplicateKeysHandling = duplicateKeysHandling;
    return this;
  }
  
  @Override
  public DataSource build(ClientContext context) {
    LDLogger logger = context.getBaseLogger().subLogger("DataSource");
    return new FileDataSourceImpl(
        context.getDataSourceUpdateSink(),
        sources,
        autoUpdate,
        duplicateKeysHandling,
        logger,
        shouldPersist
    );
  }

  /**
   * Builds an {@link Initializer} for FDv2 data system integration.
   * <p>
   * An initializer performs a one-shot load of the file data. If the file cannot be read,
   * a terminal error is returned.
   *
   * @param context the data source build context
   * @return an Initializer instance
   */
  Initializer buildInitializer(DataSourceBuildInputs context) {
    LDLogger logger = context.getBaseLogger().subLogger("FileDataSource.Initializer");
    return new FileInitializer(sources, duplicateKeysHandling, logger, shouldPersist);
  }

  /**
   * Builds a {@link Synchronizer} for FDv2 data system integration.
   * <p>
   * A synchronizer can watch for file changes (if autoUpdate is enabled) and emit
   * new change sets when files are modified.
   *
   * @param context the data source build context
   * @return a Synchronizer instance
   */
  Synchronizer buildSynchronizer(DataSourceBuildInputs context) {
    LDLogger logger = context.getBaseLogger().subLogger("FileDataSource.Synchronizer");
    return new FileSynchronizer(sources, autoUpdate, duplicateKeysHandling, logger, shouldPersist);
  }

  /**
   * Configures whether file data should be persisted to persistent stores.
   * <p>
   * By default, file data is persisted ({@code shouldPersist = true}) to maintain consistency with
   * previous versions' behavior. When {@code true}, the file data will be written to any configured persistent
   * store (if the store is in READ_WRITE mode). This may be useful for integration tests that verify
   * your persistent store configuration.
   * <p>
   * FileData synchronizers and initializers to NOT persist data by default.
   * <p>
   * Example:
   * <pre><code>
   *     FileData fd = FileData.dataSource()
   *         .filePaths("./testData/flags.json")
   *         .shouldPersist(true);
   * </code></pre>
   * <p>
   * File data
   *
   * @param shouldPersist {@code true} if data from this source should be persisted
   * @return an instance of this builder
   */
  public FileDataSourceBuilder shouldPersist(boolean shouldPersist) {
    this.shouldPersist = shouldPersist;
    return this;
  }

  static abstract class SourceInfo {
    abstract byte[] readData() throws IOException;
    abstract Path toFilePath();
  }
  
  static final class FilePathSourceInfo extends SourceInfo {
    final Path path;
    
    FilePathSourceInfo(Path path) {
      this.path = path;
    }
    
    @Override
    byte[] readData() throws IOException {
      return Files.readAllBytes(path);
    }
    
    @Override
    Path toFilePath() {
      return path;
    }
    
    @Override
    public String toString() {
      return path.toString();
    }
  }
  
  static final class ClasspathResourceSourceInfo extends SourceInfo {
    String location;
    
    ClasspathResourceSourceInfo(String location) {
      this.location = location;
    }
    
    @Override
    byte[] readData() throws IOException {
      try (InputStream is = getClass().getClassLoader().getResourceAsStream(location)) {
        if (is == null) {
          throw new IOException("classpath resource not found");
        }
        return ByteStreams.toByteArray(is);
      }
    }

    @Override
    Path toFilePath() {
      return null;
    }

    @Override
    public String toString() {
      return "classpath:" + location;
    }
  }
}