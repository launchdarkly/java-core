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

    // The enable paramter is taken outside of the Meta to be a top level value of
    // AiConfig

    /**
     * Constructor for Meta with all required fields.
     * 
     * @param variationKey the variation key
     * @param version      the version
     */
    Meta(String variationKey, Optional<Integer> version) {
        this.variationKey = variationKey;
        this.version = version != null ? version : Optional.empty();
    }

    public String getVariationKey() {
        return variationKey;
    }

    public Optional<Integer> getVersion() {
        return version;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String variationKey;
        private Optional<Integer> version = Optional.empty();

        private Builder() {
        }

        public Builder variationKey(String variationKey) {
            this.variationKey = variationKey;
            return this;
        }

        public Builder version(Optional<Integer> version) {
            this.version = version;
            return this;
        }

        public Builder version(int version) {
            this.version = Optional.of(version);
            return this;
        }

        public Meta build() {
            return new Meta(variationKey, version);
        }
    }
}
