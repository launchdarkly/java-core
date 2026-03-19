package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.fdv2.SourceResultType;
import com.launchdarkly.sdk.fdv2.SourceSignal;
import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;
import com.launchdarkly.sdk.server.datasources.Initializer;
import com.launchdarkly.sdk.server.integrations.FileDataSourceBuilder.SourceInfo;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * One-shot file loading implementation for FDv2 initialization.
 * <p>
 * This implements the {@link Initializer} interface, loading files once and returning
 * the result. If loading fails, it returns a terminal error since an initializer
 * cannot retry.
 * <p>
 * Internally delegates to {@link FileSynchronizer} with auto-update disabled.
 */
final class FileInitializer implements Initializer {
    private final FileSynchronizer synchronizer;

    FileInitializer(
            List<SourceInfo> sources,
            FileData.DuplicateKeysHandling duplicateKeysHandling,
            LDLogger logger,
            boolean persist
    ) {
        // Use FileSynchronizer with autoUpdate=false for the actual file loading
        this.synchronizer = new FileSynchronizer(sources, false, duplicateKeysHandling, logger, persist);
    }

    @Override
    public CompletableFuture<FDv2SourceResult> run() {
        return synchronizer.next().thenApply(result -> {
            // Convert INTERRUPTED to TERMINAL_ERROR for initializer semantics
            // (initializers can't retry, so all errors are terminal)
            if (result.getResultType() == SourceResultType.STATUS &&
                    result.getStatus().getState() == SourceSignal.INTERRUPTED) {
                return FDv2SourceResult.terminalError(
                        result.getStatus().getErrorInfo(),
                        result.isFdv1Fallback()
                );
            }
            return result;
        });
    }

    @Override
    public void close() throws IOException {
        synchronizer.close();
    }
}
