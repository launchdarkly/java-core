package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.server.datasources.Synchronizer;

class SynchronizerFactoryWithState {
    public enum State {
        /**
         * This synchronizer is available to use.
         */
        Available,

        /**
         * This synchronizer is no longer available to use.
         */
        Blocked
    }

    private final FDv2DataSource.DataSourceFactory<Synchronizer> factory;

    private State state = State.Available;

    private boolean isFDv1Fallback = false;

    /**
     * The number of unexpected failures since the synchronizer last reported that it met its reset threshold.
     */
    private int parkCount = 0;

    /**
     * The time at which the synchronizer stops being parked, or null if it is not parked.
     */
    private MonotonicTime unparkTime = null;


    public SynchronizerFactoryWithState(FDv2DataSource.DataSourceFactory<Synchronizer> factory) {
        this.factory = factory;
    }

    public SynchronizerFactoryWithState(FDv2DataSource.DataSourceFactory<Synchronizer> factory, boolean isFDv1Fallback) {
        this.factory = factory;
        this.isFDv1Fallback = isFDv1Fallback;
    }


    public State getState() {
        return state;
    }

    public void block() {
        state = State.Blocked;
    }

    public Synchronizer build() {
        return factory.build();
    }

    public void unblock() {
        state = State.Available;
    }

    public boolean isFDv1Fallback() {
        return isFDv1Fallback;
    }

    /**
     * Records an unexpected failure.
     *
     * @return the park count, including this failure
     */
    public int incrementParkCount() {
        return ++parkCount;
    }

    public void resetParkCount() {
        parkCount = 0;
    }

    public void park(MonotonicTime unparkTime) {
        this.unparkTime = unparkTime;
    }

    /**
     * The time at which the synchronizer stops being parked.
     *
     * @return the unpark time, or null if the synchronizer is not parked
     */
    public MonotonicTime getUnparkTime() {
        return unparkTime;
    }

    /**
     * Stops parking the synchronizer if its unpark time has arrived.
     *
     * @param now the current time
     */
    public void unparkIfExpired(MonotonicTime now) {
        if (unparkTime != null && !now.isBefore(unparkTime)) {
            unparkTime = null;
        }
    }

    /**
     * True if the synchronizer is available and is not parked.
     *
     * @return true if the synchronizer can be started
     */
    public boolean isEligible() {
        return state == State.Available && unparkTime == null;
    }
}
