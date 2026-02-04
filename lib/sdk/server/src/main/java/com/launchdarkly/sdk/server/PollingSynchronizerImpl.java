package com.launchdarkly.sdk.server;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.internal.collections.IterableAsyncQueue;
import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;
import com.launchdarkly.sdk.server.datasources.SelectorSource;
import com.launchdarkly.sdk.server.datasources.Synchronizer;

import java.time.Duration;
import java.util.concurrent.*;

class PollingSynchronizerImpl extends PollingBase implements Synchronizer {
    private final CompletableFuture<FDv2SourceResult> shutdownFuture = new CompletableFuture<>();
    private final SelectorSource selectorSource;

    private final ScheduledFuture<?> task;

    private final IterableAsyncQueue<FDv2SourceResult> resultQueue = new IterableAsyncQueue<>();

    public PollingSynchronizerImpl(
            FDv2Requestor requestor,
            LDLogger logger,
            SelectorSource selectorSource,
            ScheduledExecutorService sharedExecutor,
            Duration pollInterval
    ) {
        super(requestor, logger.subLogger(Loggers.POLLING_SYNCHRONIZER));
        this.selectorSource = selectorSource;

        synchronized (this) {
            task = sharedExecutor.scheduleAtFixedRate(
                    this::doPoll,
                    0L,
                    pollInterval.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    private void doPoll() {
        try {
            FDv2SourceResult res = poll(selectorSource.getSelector(), false).get();
            boolean shouldShutdown = false;
            switch (res.getResultType()) {
                case CHANGE_SET:
                    break;
                case STATUS:
                    switch (res.getStatus().getState()) {
                        case INTERRUPTED:
                            break;
                        case SHUTDOWN:
                            // The base poller doesn't emit shutdown, we instead handle it at this level.
                            // So when shutdown is called, we return shutdown on subsequent calls to next.
                            break;
                        case TERMINAL_ERROR:
                            synchronized (this) {
                                task.cancel(true);
                            }
                            internalShutdown();
                            shouldShutdown = true;
                            break;
                        case GOODBYE:
                            // We don't need to take any action, as the connection for the poll
                            // should already be complete. For a persistent connection we would want
                            // to proactively disconnect the stream.
                            break;
                    }
                    break;
            }
            if (shouldShutdown) {
                shutdownFuture.complete(res);
            } else {
                resultQueue.put(res);
            }
        } catch (InterruptedException e) {
            // This would likely be the result of a shutdown, so we are just logging this for debugging purposes.
            // Same with the ExecutionException below.
            logger.debug("Polling thread interrupted: {}", e.toString());
            Thread.currentThread().interrupt();
        }
        catch(ExecutionException e) {
            logger.debug("Polling thread execution exception: {}", e.toString());
        }
    }

    @Override
    public CompletableFuture<FDv2SourceResult> next() {
        return CompletableFuture.anyOf(shutdownFuture, resultQueue.take())
                .thenApply(result -> (FDv2SourceResult) result);
    }

    @Override
    public void close() {
        shutdownFuture.complete(FDv2SourceResult.shutdown());
        synchronized (this) {
            task.cancel(true);
        }
        internalShutdown();
    }
}
