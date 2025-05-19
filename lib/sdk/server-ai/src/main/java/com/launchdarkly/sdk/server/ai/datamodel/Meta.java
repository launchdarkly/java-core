package com.launchdarkly.sdk.server.ai.datamodel;

import java.util.Optional;

public final class Meta {
    /**
     * The variation key.
     */
    private final String variationKey;

    /**
     * The variation version.
     */
    private final Optional<Integer> version;

    /**
     * If the config is enabled.
     */
    // private final boolean enabled;

    /**
     * Constructor for Meta with all required fields.
     * 
     * @param variationKey the variation key
     * @param version the version
     */
    public Meta(String variationKey, Optional<Integer> version) {
        this.variationKey = variationKey;
        this.version = version != null ? version : Optional.empty();
        // this.enabled = enabled;
    }

    public String getVariationKey() {
        return variationKey;
    }

    public Optional<Integer> getVersion() {
        return version;
    }

    // public boolean isEnabled() {
    //     return enabled;
    // }
}
