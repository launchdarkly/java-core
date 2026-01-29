package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;
import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;
import com.launchdarkly.sdk.server.datasources.Synchronizer;
import com.launchdarkly.sdk.server.integrations.FileDataSourceBuilder.SourceInfo;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.Watchable;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

/**
 * Streaming file updates implementation for FDv2 synchronization.
 * <p>
 * This implements the {@link Synchronizer} interface, providing file watching
 * and emitting results when files change. If autoUpdate is disabled, it only
 * returns the initial load result.
 */
final class FileSynchronizer extends FileDataSourceBase implements Synchronizer {
    private final CompletableFuture<FDv2SourceResult> shutdownFuture = new CompletableFuture<>();
    private final AsyncQueue<FDv2SourceResult> resultQueue = new AsyncQueue<>();
    private final FileWatcher fileWatcher;  // null if autoUpdate=false
    private volatile boolean started = false;

    FileSynchronizer(
            List<SourceInfo> sources,
            boolean autoUpdate,
            FileData.DuplicateKeysHandling duplicateKeysHandling,
            LDLogger logger,
            boolean persist
    ) {
        super(sources, duplicateKeysHandling, logger, persist);

        FileWatcher fw = null;
        if (autoUpdate) {
            try {
                fw = FileWatcher.create(getSources(), logger);
            } catch (IOException e) {
                // COVERAGE: there is no way to simulate this condition in a unit test
                logger.error("Unable to watch files for auto-updating: {}", e.toString());
                logger.debug(e.toString(), e);
                fw = null;
            }
        }
        this.fileWatcher = fw;
    }

    @Override
    public CompletableFuture<FDv2SourceResult> next() {
        if (!started) {
            started = true;
            // Perform initial load
            resultQueue.put(loadData(false));
            // Start file watching if enabled
            if (fileWatcher != null) {
                fileWatcher.start(this::onFileChange);
            }
        }
        return CompletableFuture.anyOf(shutdownFuture, resultQueue.take())
                .thenApply(result -> (FDv2SourceResult) result);
    }

    private void onFileChange() {
        resultQueue.put(loadData(false));
    }

    @Override
    public void close() {
        shutdownFuture.complete(FDv2SourceResult.shutdown());
        if (fileWatcher != null) {
            fileWatcher.stop();
        }
    }

    /**
     * A simple thread-safe async queue for passing results between the file watcher and the synchronizer.
     */
    private static final class AsyncQueue<T> {
        private final Object lock = new Object();
        private final LinkedList<T> queue = new LinkedList<>();
        private final LinkedList<CompletableFuture<T>> pendingFutures = new LinkedList<>();

        public void put(T item) {
            synchronized (lock) {
                CompletableFuture<T> nextFuture = pendingFutures.pollFirst();
                if (nextFuture != null) {
                    nextFuture.complete(item);
                    return;
                }
                queue.addLast(item);
            }
        }

        public CompletableFuture<T> take() {
            synchronized (lock) {
                if (!queue.isEmpty()) {
                    return CompletableFuture.completedFuture(queue.removeFirst());
                }
                CompletableFuture<T> takeFuture = new CompletableFuture<>();
                pendingFutures.addLast(takeFuture);
                return takeFuture;
            }
        }
    }

    /**
     * If auto-updating is enabled, this component watches for file changes on a worker thread.
     */
    private static final class FileWatcher implements Runnable {
        private final WatchService watchService;
        private final Set<Path> watchedFilePaths;
        private Runnable fileModifiedAction;
        private final Thread thread;
        private final LDLogger logger;
        private volatile boolean stopped;

        private static FileWatcher create(Iterable<SourceInfo> sources, LDLogger logger) throws IOException {
            Set<Path> directoryPaths = new HashSet<>();
            Set<Path> absoluteFilePaths = new HashSet<>();
            FileSystem fs = FileSystems.getDefault();
            WatchService ws = fs.newWatchService();

            // In Java, you watch for filesystem changes at the directory level, not for individual files.
            for (SourceInfo s : sources) {
                Path p = s.toFilePath();
                if (p != null) {
                    // Convert to absolute path to ensure we have a parent directory
                    // (relative paths like "flags.json" have null parent)
                    Path absolutePath = p.toAbsolutePath();
                    absoluteFilePaths.add(absolutePath);
                    directoryPaths.add(absolutePath.getParent());
                }
            }
            for (Path d : directoryPaths) {
                d.register(ws, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
            }

            return new FileWatcher(ws, absoluteFilePaths, logger);
        }

        private FileWatcher(WatchService watchService, Set<Path> watchedFilePaths, LDLogger logger) {
            this.watchService = watchService;
            this.watchedFilePaths = watchedFilePaths;
            this.logger = logger;

            thread = new Thread(this, FileSynchronizer.class.getName());
            thread.setDaemon(true);
        }

        public void run() {
            while (!stopped) {
                try {
                    WatchKey key = watchService.take(); // blocks until a change is available or we are interrupted
                    boolean watchedFileWasChanged = false;
                    for (WatchEvent<?> event : key.pollEvents()) {
                        Watchable w = key.watchable();
                        Object context = event.context();
                        if (w instanceof Path && context instanceof Path) {
                            Path dirPath = (Path) w;
                            Path fileNamePath = (Path) context;
                            Path absolutePath = dirPath.resolve(fileNamePath);
                            if (watchedFilePaths.contains(absolutePath)) {
                                watchedFileWasChanged = true;
                                break;
                            }
                        }
                    }
                    if (watchedFileWasChanged) {
                        try {
                            fileModifiedAction.run();
                        } catch (Exception e) {
                            // COVERAGE: there is no way to simulate this condition in a unit test
                            logger.warn("Unexpected exception when reloading file data: {}", LogValues.exceptionSummary(e));
                        }
                    }
                    key.reset(); // if we don't do this, the watch on this key stops working
                } catch (InterruptedException e) {
                    // if we've been stopped we will drop out at the top of the while loop
                }
            }
        }

        public void start(Runnable fileModifiedAction) {
            this.fileModifiedAction = fileModifiedAction;
            thread.start();
        }

        public void stop() {
            stopped = true;
            thread.interrupt();
        }
    }
}
