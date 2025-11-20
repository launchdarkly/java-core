package com.launchdarkly.sdk.internal.fdv2.processor;

import java.util.List;

import static java.util.Collections.emptyList;

/**
 * Represents a collection of updates from the FDv2 services. If basis is true, the set of updates
 * represents the complete state of the payload.
 */
public final class Payload {
    private String id;
    private int version;
    private String state;
    private boolean basis;
    private List<Update> updates;

    /**
     * Default constructor.
     */
    public Payload() {}

    /**
     * Constructs a Payload with the specified properties.
     *
     * @param id the payload identifier
     * @param version the payload version
     * @param state the payload state (optional)
     * @param basis whether this represents the complete state
     * @param updates the list of updates
     */
    public Payload(String id, int version, String state, boolean basis, List<Update> updates) {
        this.id = id;
        this.version = version;
        this.state = state;
        this.basis = basis;
        this.updates = updates;
    }

    /**
     * Returns the payload identifier.
     *
     * @return the payload identifier
     */
    public String getId() {
        return id;
    }

    /**
     * Sets the payload identifier.
     *
     * @param id the payload identifier
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * Returns the payload version.
     *
     * @return the payload version
     */
    public int getVersion() {
        return version;
    }

    /**
     * Sets the payload version.
     *
     * @param version the payload version
     */
    public void setVersion(int version) {
        this.version = version;
    }

    /**
     * Returns the payload state.
     *
     * @return the payload state, or null if not present
     */
    public String getState() {
        return state;
    }

    /**
     * Sets the payload state.
     *
     * @param state the payload state
     */
    public void setState(String state) {
        this.state = state;
    }

    /**
     * Returns whether this represents the complete state.
     *
     * @return true if this represents the complete state, false otherwise
     */
    public boolean isBasis() {
        return basis;
    }

    /**
     * Sets whether this represents the complete state.
     *
     * @param basis true if this represents the complete state, false otherwise
     */
    public void setBasis(boolean basis) {
        this.basis = basis;
    }

    /**
     * Returns the list of updates.
     *
     * @return the list of updates (never null)
     */
    public List<Update> getUpdates() {
        return updates == null ? emptyList() : updates;
    }

    /**
     * Sets the list of updates.
     *
     * @param updates the list of updates
     */
    public void setUpdates(List<Update> updates) {
        this.updates = updates;
    }
}

