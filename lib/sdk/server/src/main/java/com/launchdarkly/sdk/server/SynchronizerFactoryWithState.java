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
}
