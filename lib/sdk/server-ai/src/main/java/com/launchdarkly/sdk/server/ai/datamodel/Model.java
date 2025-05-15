package com.launchdarkly.sdk.server.ai.datamodel;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.launchdarkly.sdk.LDValue;

public final class Model {
    private final String name;

    private final Map<String, LDValue> parameters;

    private final Map<String, LDValue> custom;

    /**
     * Constructor for Model with all required fields.
     * 
     * @param name the model name
     * @param parameters the parameters map
     * @param custom the custom map
     */
    public Model(String name, Map<String, LDValue> parameters, Map<String, LDValue> custom) {
        this.name = name;
        this.parameters = parameters != null ? Collections.unmodifiableMap(new HashMap<>(parameters)) : Collections.emptyMap();
        this.custom = custom != null ? Collections.unmodifiableMap(new HashMap<>(custom)) : Collections.emptyMap();
    }

    public String getName() {
        return name;
    }

    public Map<String, LDValue> getParameters() {
        return parameters;
    }

    public Map<String, LDValue> getCustom() {
        return custom;
    }
}
