package com.launchdarkly.sdk.server.ai.datamodel;

public final class Provider {
    private String name;

    public Provider(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
