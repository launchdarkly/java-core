package com.launchdarkly.sdk.server.ai.tracking;

/**
 * Represents token usage.
 */
public final class Usage {
    private final Integer total;
    private final Integer input;
    private final Integer output;

    public Usage(Integer total, Integer input, Integer output) {
        this.total = total;
        this.input = input;
        this.output = output;
    }

    public Integer getTotal() {
        return total;
    }

    public Integer getInput() {
        return input;
    }

    public Integer getOutput() {
        return output;
    }
}
