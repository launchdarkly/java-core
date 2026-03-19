package com.launchdarkly.sdk.server.datasources;

import com.launchdarkly.sdk.fdv2.Selector;

/**
 * This type is currently experimental and not subject to semantic versioning.
 * <p>
 * Source of selectors for FDv2 implementations.
 */
public interface SelectorSource {
    /**
     * Get the current selector.
     * @return The current selector.
     */
    Selector getSelector();
}
