package com.launchdarkly.sdk.server.datasources;

/**
 * This type is currently experimental and not subject to semantic versioning.
 * <p>
 * Interface used to shut down a data source.
 */
public interface DataSourceShutdown {
    /**
     * Shutdown the data source. The data source should emit a status event with a SHUTDOWN state as soon as possible.
     * If the data source has already completed, or is in the process of completing, this method should have no effect.
     */
    void shutdown();
}
