package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;
import com.launchdarkly.sdk.server.datasources.Initializer;
import com.launchdarkly.sdk.server.integrations.FileDataSourceBuilder.SourceInfo;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * One-shot file loading implementation for FDv2 initialization.
 * <p>
 * This implements the {@link Initializer} interface, loading files once and returning
 * the result. If loading fails, it returns a terminal error since an initializer
 * cannot retry.
 */
final class FileInitializer extends FileDataSourceBase implements Initializer {
    private final CompletableFuture<FDv2SourceResult> shutdownFuture = new CompletableFuture<>();

    FileInitializer(
            List<SourceInfo> sources,
            FileData.DuplicateKeysHandling duplicateKeysHandling,
            LDLogger logger,
            boolean persist
    ) {
        super(sources, duplicateKeysHandling, logger, persist);
    }

    @Override
    public CompletableFuture<FDv2SourceResult> run() {
        CompletableFuture<FDv2SourceResult> loadResult = CompletableFuture.supplyAsync(() -> loadData(true));
        return CompletableFuture.anyOf(shutdownFuture, loadResult)
                .thenApply(result -> (FDv2SourceResult) result);
    }

    @Override
    public void close() {
        shutdownFuture.complete(FDv2SourceResult.shutdown());
    }
}
