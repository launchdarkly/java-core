package com.launchdarkly.sdk.server.ai.datamodel;

public final class Provider {
    private final String name;

    Provider(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static final class Builder {
        private String name;
        
        private Builder() {}
        
        public Builder name(String name) {
            this.name = name;
            return this;
        }
        
        public Provider build() {
            return new Provider(name);
        }
    }
}
