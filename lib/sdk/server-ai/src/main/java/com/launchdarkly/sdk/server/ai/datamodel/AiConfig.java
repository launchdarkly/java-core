package com.launchdarkly.sdk.server.ai.datamodel;

import java.util.List;

public final class AiConfig {
    private final boolean enabled;

    private final Meta meta;

    private final Model model;

    private final List<Message> messages;

    private final Provider provider;

    public AiConfig(boolean enabled, Meta meta, Model model, List<Message> messages, Provider provider) {
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
}
