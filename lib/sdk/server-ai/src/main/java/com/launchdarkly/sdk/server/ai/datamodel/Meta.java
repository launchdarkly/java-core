package com.launchdarkly.sdk.server.ai.datamodel;

import java.util.Optional;

public final class Meta {
    /**
     * The variation key.
     */
    private String variationKey;

    /**
     * The variation version.
     */
    private Optional<Integer> version;

    /**
     * If the config is enabled.
     */
    private boolean enabled;

    // Getters and Setters

    public String getVariationKey() {
        return variationKey;
    }

    public void setVariationKey(String variationKey) {
        this.variationKey = variationKey;
    }

    public Optional<Integer> getVersion() {
        return version;
    }

    public void setVersion(Optional<Integer> version) {
        this.version = version;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
