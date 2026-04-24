package com.launchdarkly.sdk.server.datasources;

import java.io.Closeable;
import java.util.concurrent.CompletableFuture;

// Mermaid source for state diagram.
// stateDiagram-v2
//     [*] --> RUNNING
//     RUNNING --> SHUTDOWN
//     SHUTDOWN --> [*]
//     RUNNING --> TERMINAL_ERROR
//     TERMINAL_ERROR --> [*]
//     RUNNING --> GOODBYE
//     GOODBYE --> RUNNING
//     RUNNING --> CHANGE_SET
//     CHANGE_SET --> RUNNING
//     RUNNING --> INTERRUPTED
//     INTERRUPTED --> RUNNING

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
 *     │   │   │   │   │   └──► SHUTDOWN ───────────► [END]
 *     │   │   │   │   │
 *     │   │   │   │   └──────► TERMINAL_ERROR ─────► [END]
 *     │   │   │   │
 *     │   │   │   └──────────► GOODBYE ────────────┐
 *     │   │   │                                    │
 *     │   │   └──────────────► CHANGE_SET ─────────┤
 *     │   │                                        │
 *     │   └──────────────────► INTERRUPTED ────────┤
 *     │                                            │
 *     └────────────────────────────────────────────┘
 */
public interface Synchronizer extends Closeable {
    /**
     * Get the next result from the stream.
     * <p>
     * This method is intended to be driven by a single thread, and for there to be a single outstanding call
     * at any given time.
     * <p>
     *  Once SHUTDOWN or TERMINAL_ERROR, has been produced, then no further calls to next() should be made.
     * @return a future that will complete when the next result is available
     */
    CompletableFuture<FDv2SourceResult> next();

    /**
     * Human-readable name for logging and diagnostics. Do not use this for influencing code behavior.
     * <p>
     * Implementations may override; the default uses the runtime class simple name.
     *
     * @return the name
     */
    default String name() {
        String simple = getClass().getSimpleName();
        return simple.isEmpty() ? getClass().getName() : simple;
    }
}
