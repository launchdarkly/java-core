package com.launchdarkly.sdk.server.subsystems;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.internal.events.DiagnosticStore;
import com.launchdarkly.sdk.server.datasources.SelectorSource;
import com.launchdarkly.sdk.server.interfaces.ServiceEndpoints;

import java.util.concurrent.ScheduledExecutorService;

/**
 * Build information (dependencies and configuration) provided to initializer and synchronizer builders.
 * <p>
 * This class is not stable, and not subject to any backwards compatibility guarantees or semantic versioning.
 * It is in early access. If you want access to this feature, please join the EAP. https://launchdarkly.com/docs/sdk/features/data-saving-mode
 * <p>
 * This consolidates all the parameters needed to construct data source components,
 * including HTTP configuration, logging, scheduling, and selector state.
 */
public final class DataSourceBuildInputs {
    private final LDLogger baseLogger;
    private final int threadPriority;
    private final DataSourceUpdateSink dataSourceUpdates;
    private final ServiceEndpoints serviceEndpoints;
    private final HttpConfiguration http;
    private final ScheduledExecutorService sharedExecutor;
    private final DiagnosticStore diagnosticStore;
    private final SelectorSource selectorSource;

    /**
     * Constructs a DataSourceBuilderContext.
     *
     * @param baseLogger the base logger instance
     * @param threadPriority the thread priority for worker threads
     * @param dataSourceUpdates the data source update sink
     * @param serviceEndpoints the service endpoint URIs
     * @param http HTTP configuration properties
     * @param sharedExecutor shared executor service for scheduling
     * @param diagnosticStore diagnostic data accumulator (may be null)
     * @param selectorSource source for obtaining selectors
     */
    public DataSourceBuildInputs(
            LDLogger baseLogger,
            int threadPriority,
            DataSourceUpdateSink dataSourceUpdates,
            ServiceEndpoints serviceEndpoints,
            HttpConfiguration http,
            ScheduledExecutorService sharedExecutor,
            DiagnosticStore diagnosticStore,
            SelectorSource selectorSource
    ) {
        this.baseLogger = baseLogger;
        this.threadPriority = threadPriority;
        this.dataSourceUpdates = dataSourceUpdates;
        this.serviceEndpoints = serviceEndpoints;
        this.http = http;
        this.sharedExecutor = sharedExecutor;
        this.diagnosticStore = diagnosticStore;
        this.selectorSource = selectorSource;
    }

    /**
     * Returns the base logger instance.
     *
     * @return the base logger
     */
    public LDLogger getBaseLogger() {
        return baseLogger;
    }

    /**
     * Returns the thread priority for worker threads.
     *
     * @return the thread priority
     */
    public int getThreadPriority() {
        return threadPriority;
    }

    /**
     * Returns the data source update sink.
     *
     * @return the data source update sink
     */
    public DataSourceUpdateSink getDataSourceUpdates() {
        return dataSourceUpdates;
    }

    /**
     * Returns the service endpoint URIs.
     *
     * @return the service endpoints
     */
    public ServiceEndpoints getServiceEndpoints() {
        return serviceEndpoints;
    }

    /**
     * Returns the HTTP configuration properties.
     *
     * @return the HTTP configuration
     */
    public HttpConfiguration getHttp() {
        return http;
    }

    /**
     * Returns the shared executor service for scheduling.
     *
     * @return the shared executor
     */
    public ScheduledExecutorService getSharedExecutor() {
        return sharedExecutor;
    }

    /**
     * Returns the diagnostic data accumulator.
     *
     * @return the diagnostic store, or null if diagnostics are disabled
     */
    public DiagnosticStore getDiagnosticStore() {
        return diagnosticStore;
    }

    /**
     * Returns the selector source.
     *
     * @return the selector source
     */
    public SelectorSource getSelectorSource() {
        return selectorSource;
    }
}
