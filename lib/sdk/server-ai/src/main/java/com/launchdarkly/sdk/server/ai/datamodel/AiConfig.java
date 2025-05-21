package com.launchdarkly.sdk.server.ai.datamodel;

import java.util.List;

public final class AiConfig {
    private final boolean enabled;

    private final Meta meta;

    private final Model model;

    private final List<Message> messages;

    private final Provider provider;

    AiConfig(boolean enabled, Meta meta, Model model, List<Message> messages, Provider provider) {
        this.enabled = enabled;
        this.meta = meta;
        this.model = model;
        this.messages = messages;
        this.provider = provider;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public Meta getMeta() {
        return meta;
    }

    public Model getModel() {
        return model;
    }

    public Provider getProvider() {
        return provider;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static final class Builder {
        private boolean enabled;
        private Meta meta;
        private Model model;
        private List<Message> messages;
        private Provider provider;
        
        private Builder() {}
        
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }
        
        public Builder meta(Meta meta) {
            this.meta = meta;
            return this;
        }
        
        public Builder model(Model model) {
            this.model = model;
            return this;
        }
        
        public Builder messages(List<Message> messages) {
            this.messages = messages;
            return this;
        }
        
        public Builder provider(Provider provider) {
            this.provider = provider;
            return this;
        }
        
        public AiConfig build() {
            return new AiConfig(enabled, meta, model, messages, provider);
        }
    }
}
