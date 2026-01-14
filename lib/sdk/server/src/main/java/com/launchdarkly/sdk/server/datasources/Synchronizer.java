package com.launchdarkly.sdk.server.datasources;

import java.util.concurrent.CompletableFuture;

/**
 * This type is currently experimental and not subject to semantic versioning.
 * <p>
 * Interface for an asynchronous data source synchronizer.
 * <p>
 * A synchronizer will run and produce a stream of results. When it experiences a temporary failure, it will emit a
 * status event indicating that it is INTERRUPTED, while it attempts to resolve its failure. When it receives data,
 * it should emit a result containing a change set. When the data source is shut down gracefully, it should emit a
 * status event indicating that it is SHUTDOWN.
 * <p>
 *           [START]
 *              │
 *              ▼
 *        ┌─────────────┐
 *     ┌─►│   RUNNING   │──┐
 *     │  └─────────────┘  │
 *     │   │   │   │   │   │
 *     │   │   │   │   │   └──► SHUTDOWN ───► [END]
 *     │   │   │   │   │
 *     │   │   │   │   └──────► TERMINAL_ERROR ───► [END]
 *     │   │   │   │
 *     │   │   │   └──────────► GOODBYE ───► [END]
 *     │   │   │
 *     │   │   └──────────────► CHANGE_SET ───┐
 *     │   │                                  │
 *     │   └──────────────────► INTERRUPTED ──┤
 *     │                                      │
 *     └──────────────────────────────────────┘
 * <p>
 * <pre>
 * stateDiagram-v2
 *     [*] --> RUNNING
 *     RUNNING --> SHUTDOWN
 *     SHUTDOWN --> [*]
 *     RUNNING --> TERMINAL_ERROR
 *     TERMINAL_ERROR --> [*]
 *     RUNNING --> GOODBYE
 *     GOODBYE --> [*]
 *     RUNNING --> CHANGE_SET
 *     CHANGE_SET --> RUNNING
 *     RUNNING --> INTERRUPTED
 *     INTERRUPTED --> RUNNING
 * </pre>
 */
public interface Synchronizer extends DataSourceShutdown {
    /**
     * Get the next result from the stream.
     * <p>
     * This method is intended to be driven by a single thread, and for there to be a single outstanding call
     * at any given time.
     * @return a future that will complete when the next result is available
     */
    CompletableFuture<FDv2SourceResult> next();
}
