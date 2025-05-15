package com.launchdarkly.sdk.server.ai.datamodel;

import java.util.HashMap;

import com.launchdarkly.sdk.LDValue;

public final class Model {
    private String name;

    private HashMap<String, LDValue> parameters;

    private HashMap<String, LDValue> custom;

    // Getters and Setters

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public HashMap<String, LDValue> getParameters() {
        return parameters;
    }

    public void setParameters(HashMap<String, LDValue> parameters) {
        this.parameters = parameters;
    }

    public HashMap<String, LDValue> getCustom() {
        return custom;
    }

    public void setCustom(HashMap<String, LDValue> custom) {
        this.custom = custom;
    }
}
