package com.launchdarkly.sdk.server;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/**
 * Manages the state of synchronizers including tracking which synchronizer is active,
 * managing the list of available synchronizers, and handling source transitions.
 * <p>
 * Package-private for internal use.
 */
class SynchronizerStateManager implements Closeable {
    private final List<SynchronizerFactoryWithState> synchronizers;

    /**
     * Lock for active sources and shutdown state.
     */
    private final Object activeSourceLock = new Object();
    private Closeable activeSource;
    private boolean isShutdown = false;

    /**
     * We start at -1, so finding the next synchronizer can non-conditionally increment the index.
     */
    private int sourceIndex = -1;

    public SynchronizerStateManager(List<SynchronizerFactoryWithState> synchronizers) {
        this.synchronizers = synchronizers;
    }

    /**
     * Reset the source index to -1, indicating that we should start from the first synchronizer when looking for
     * the next one to use. This is used when recovering from a non-primary synchronizer.
     */
    public void resetSourceIndex() {
        synchronized (activeSourceLock) {
            sourceIndex = -1;
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
     * @return the next synchronizer factory to use, or null if there are no more available synchronizers.
     */
    public SynchronizerFactoryWithState getNextAvailableSynchronizer() {
        synchronized (activeSourceLock) {
            SynchronizerFactoryWithState factory = null;

            int visited = 0;
            while(visited < synchronizers.size()) {
                // Look for the next synchronizer starting at the position after the current one. (avoiding just re-using the same synchronizer.)
                sourceIndex++;

                // We aren't using module here because we want to keep the stored index within range instead
                // of increasing indefinitely.
                if(sourceIndex >= synchronizers.size()) {
                    sourceIndex = 0;
                }

                SynchronizerFactoryWithState candidate = synchronizers.get(sourceIndex);
                if (candidate.getState() == SynchronizerFactoryWithState.State.Available) {
                    factory = candidate;
                    break;
                }
                visited++;
            }
            return factory;
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
                    if (sourceIndex == index) {
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
     * Set the active source. If shutdown has been initiated, the source will be closed immediately.
     * Any previously active source will be closed.
     * @param source the source to set as active
     * @return true if shutdown has been initiated, false otherwise
     */
    public boolean setActiveSource(Closeable source) {
        synchronized (activeSourceLock) {
            if (activeSource != null) {
                safeClose(activeSource);
            }
            if (isShutdown) {
                safeClose(source);
                return true;
            }
            activeSource = source;
        }
        return false;
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
        try {
            closeable.close();
        } catch (IOException e) {
            // Ignore close exceptions.
        }
    }
}
