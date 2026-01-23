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


    public SynchronizerFactoryWithState(FDv2DataSource.DataSourceFactory<Synchronizer> factory) {
        this.factory = factory;
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
}
