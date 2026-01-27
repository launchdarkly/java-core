package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.internal.fdv2.sources.Selector;
import com.launchdarkly.sdk.server.datasources.SelectorSource;
import com.launchdarkly.sdk.server.subsystems.TransactionalDataStore;

class SelectorSourceFacade implements SelectorSource {
    private final TransactionalDataStore store;
    public SelectorSourceFacade(TransactionalDataStore store) {
        this.store = store;
    }

    @Override
    public Selector getSelector() {
        return store.getSelector();
    }
}
