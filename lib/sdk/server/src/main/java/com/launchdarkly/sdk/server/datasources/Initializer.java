package com.launchdarkly.sdk.server.datasources;

import java.io.Closeable;
import java.util.concurrent.CompletableFuture;

// Mermaid source for state diagram.
// stateDiagram-v2
//     [*] --> RUNNING
//     RUNNING --> SHUTDOWN
//     RUNNING --> INTERRUPTED
//     RUNNING --> CHANGESET
//     RUNNING --> TERMINAL_ERROR
//     RUNNING --> GOODBYE
//     SHUTDOWN --> [*]
//     INTERRUPTED --> [*]
//     CHANGESET --> [*]
//     TERMINAL_ERROR --> [*]
//     GOODBYE --> [*]

/**
 * This type is currently experimental and not subject to semantic versioning.
 * <p>
 * Interface for an asynchronous data source initializer.
 * <p>
 * An initializer will run and produce a single result. If the initializer is successful, then it should emit a result
 * containing a change set. If the initializer fails, then it should emit a status result describing the error.
 * <p>
 *           [START]
 *              │
 *              ▼
 *        ┌─────────────┐
 *        │   RUNNING   │──┐
 *        └─────────────┘  │
 *          │   │   │   │  │
 *          │   │   │   │  └──► SHUTDOWN ───► [END]
 *          │   │   │   │
 *          │   │   │   └─────► INTERRUPTED ───► [END]
 *          │   │   │
 *          │   │   └─────────► CHANGESET ───► [END]
 *          │   │
 *          │   └─────────────► TERMINAL_ERROR ───► [END]
 *          │
 *          └─────────────────► GOODBYE ───► [END]
 *
 */
public interface Initializer extends Closeable {
    /**
     * Run the initializer to completion.
     * @return The result of the initializer.
     */
    CompletableFuture<FDv2SourceResult> run();
}
