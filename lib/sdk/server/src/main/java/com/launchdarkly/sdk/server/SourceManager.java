package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.server.datasources.Initializer;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/**
 * Manages the state of synchronizers including tracking which synchronizer is active,
 * managing the list of available synchronizers, and handling source transitions.
 * <p>
 * Package-private for internal use.
 */
class SourceManager implements Closeable {
    private final List<SynchronizerFactoryWithState> synchronizers;

    private final List<FDv2DataSource.DataSourceFactory<Initializer>> initializers;

    /**
     * Lock for active sources and shutdown state.
     */
    private final Object activeSourceLock = new Object();
    private Closeable activeSource;
    private boolean isShutdown = false;

    /**
     * We start at -1, so finding the next synchronizer can non-conditionally increment the index.
     */
    private int synchronizerIndex = -1;

    private int initializerIndex = -1;

    /**
     * The current synchronizer factory (for checking FDv1 fallback status and blocking)
     */
    private SynchronizerFactoryWithState currentSynchronizerFactory;

    public SourceManager(List<SynchronizerFactoryWithState> synchronizers, List<FDv2DataSource.DataSourceFactory<Initializer>> initializers) {
        this.synchronizers = synchronizers;
        this.initializers = initializers;
    }

    /**
     * Reset the source index to -1, indicating that we should start from the first synchronizer when looking for
     * the next one to use. This is used when recovering from a non-primary synchronizer.
     */
    public void resetSourceIndex() {
        synchronized (activeSourceLock) {
            synchronizerIndex = -1;
        }
    }

    public boolean hasFDv1Fallback() {
        for (SynchronizerFactoryWithState factory : synchronizers) {
            if (factory.isFDv1Fallback()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Block all synchronizers aside from the fdv1 fallback and unblock the fdv1 fallback.
     */
    public void fdv1Fallback() {
        for (SynchronizerFactoryWithState factory : synchronizers) {
            if(factory.isFDv1Fallback()) {
                factory.unblock();
            } else {
                factory.block();
            }
        }
    }

    /**
     * Get the next synchronizer to use. This operates based on tracking the index of the currently active synchronizer,
     * which will loop through all available synchronizers handling interruptions. Then a non-prime synchronizer recovers
     * the source index will be reset, and we start at the beginning.
     * <p>
     * Any given synchronizer can be marked as blocked, in which case that synchronizer is not eligible to be used again.
     * Synchronizers that are not blocked are available, and this function will only return available synchronizers.
     * <p>
     * <b>Note:</b> This is an internal method that must be called while holding activeSourceLock.
     * It does not check shutdown status or handle locking - that's done by the caller.
     *
     * @return the next synchronizer factory to use, or null if there are no more available synchronizers.
     */
    private SynchronizerFactoryWithState getNextAvailableSynchronizer() {
        SynchronizerFactoryWithState factory = null;

        int visited = 0;
        while(visited < synchronizers.size()) {
            // Look for the next synchronizer starting at the position after the current one. (avoiding just re-using the same synchronizer.)
            synchronizerIndex++;

            // We aren't using module here because we want to keep the stored index within range instead
            // of increasing indefinitely.
            if(synchronizerIndex >= synchronizers.size()) {
                synchronizerIndex = 0;
            }

            SynchronizerFactoryWithState candidate = synchronizers.get(synchronizerIndex);
            if (candidate.getState() == SynchronizerFactoryWithState.State.Available) {
                factory = candidate;
                break;
            }
            visited++;
        }
        return factory;
    }

    /**
     * Get the next available synchronizer, build it, and set it as the active source in one atomic operation.
     * This combines the two-step process of getting the next synchronizer and setting it active.
     * <p>
     * If shutdown has been initiated, returns null without building or setting a source.
     * Any previously active source will be closed before setting the new one.
     * <p>
     * The current synchronizer factory can be retrieved with {@link #blockCurrentSynchronizer()}
     * or {@link #isCurrentSynchronizerFDv1Fallback()} to interact with it.
     *
     * @return the built synchronizer that is now active, or null if no more synchronizers are available or shutdown has been initiated
     */
    public com.launchdarkly.sdk.server.datasources.Synchronizer getNextAvailableSynchronizerAndSetActive() {
        synchronized (activeSourceLock) {
            // Handle shutdown first - if shutdown, don't do any work
            if (isShutdown) {
                currentSynchronizerFactory = null;
                return null;
            }

            SynchronizerFactoryWithState factory = getNextAvailableSynchronizer();
            if (factory == null) {
                currentSynchronizerFactory = null;
                return null;
            }

            currentSynchronizerFactory = factory;
            com.launchdarkly.sdk.server.datasources.Synchronizer synchronizer = factory.build();

            // Close any previously active source
            if (activeSource != null) {
                safeClose(activeSource);
            }

            activeSource = synchronizer;
            return synchronizer;
        }
    }


    public boolean hasAvailableSources() {
        return hasInitializers() || getAvailableSynchronizerCount() > 0;
    }

    public boolean hasInitializers() {
        return !initializers.isEmpty();
    }

    public boolean hasAvailableSynchronizers() {
        return getAvailableSynchronizerCount() > 0;
    }

    /**
     * Get the next initializer factory. This is an internal method that must be called while holding activeSourceLock.
     * It does not check shutdown status or handle locking - that's done by the caller.
     *
     * @return the next initializer factory, or null if no more initializers are available
     */
    private FDv2DataSource.DataSourceFactory<Initializer> getNextInitializer() {
        initializerIndex++;
        if (initializerIndex >= initializers.size()) {
            return null;
        }
        return initializers.get(initializerIndex);
    }

    public void blockCurrentSynchronizer() {
        synchronized (activeSourceLock) {
            if (currentSynchronizerFactory != null) {
                currentSynchronizerFactory.block();
            }
        }
    }

    public boolean isCurrentSynchronizerFDv1Fallback() {
        synchronized (activeSourceLock) {
            return currentSynchronizerFactory != null && currentSynchronizerFactory.isFDv1Fallback();
        }
    }

    /**
     * Get the next initializer, build it, and set it as the active source in one atomic operation.
     * This combines the two-step process of getting the next initializer and setting it active.
     * <p>
     * If shutdown has been initiated, returns null without building or setting a source.
     * Any previously active source will be closed before setting the new one.
     *
     * @return the built initializer that is now active, or null if no more initializers are available or shutdown has been initiated
     */
    public Initializer getNextInitializerAndSetActive() {
        synchronized (activeSourceLock) {
            // Handle shutdown first - if shutdown, don't do any work
            if (isShutdown) {
                return null;
            }

            FDv2DataSource.DataSourceFactory<Initializer> factory = getNextInitializer();
            if (factory == null) {
                return null;
            }

            Initializer initializer = factory.build();

            // Close any previously active source
            if (activeSource != null) {
                safeClose(activeSource);
            }

            activeSource = initializer;
            return initializer;
        }
    }

    /**
     * Determine if the currently active synchronizer is the prime (first available) synchronizer.
     * @return true if the current synchronizer is the prime synchronizer, false otherwise
     */
    public boolean isPrimeSynchronizer() {
        synchronized (activeSourceLock) {
            for (int index = 0; index < synchronizers.size(); index++) {
                if (synchronizers.get(index).getState() == SynchronizerFactoryWithState.State.Available) {
                    if (synchronizerIndex == index) {
                        // This is the first synchronizer that is available, and it also is the current one.
                        return true;
                    }
                    break;
                    // Subsequently encountered synchronizers that are available are not the first one.
                }
            }
        }
        return false;
    }

    /**
     * Get the count of available synchronizers.
     * @return the number of available synchronizers
     */
    public int getAvailableSynchronizerCount() {
        synchronized (activeSourceLock) {
            int count = 0;
            for (int index = 0; index < synchronizers.size(); index++) {
                if (synchronizers.get(index).getState() == SynchronizerFactoryWithState.State.Available) {
                    count++;
                }
            }
            return count;
        }
    }


    /**
     * Close the state manager and shut down any active source.
     * Implements AutoCloseable to enable try-with-resources usage.
     */
    @Override
    public void close() {
        synchronized (activeSourceLock) {
            isShutdown = true;
            if (activeSource != null) {
                try {
                    activeSource.close();
                } catch (IOException e) {
                    // We are done with this synchronizer, so we don't care if it encounters
                    // an error condition.
                }
                activeSource = null;
            }
        }
    }

    /**
     * Safely close a closeable, ignoring any exceptions.
     * @param closeable the closeable to close
     */
    private void safeClose(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException e) {
            // Ignore close exceptions.
        }
    }
}
