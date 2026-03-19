package com.launchdarkly.sdk.fdv2;

import java.util.Objects;

/**
 * Represents a set of changes to apply to a data store.
 *
 * @param <T> the type of the data payload
 */
public final class ChangeSet<T> {
  private final ChangeSetType type;
  private final Selector selector;
  private final String environmentId;
  private final T data;
  private final boolean shouldPersist;

  /**
   * Constructs a new ChangeSet.
   * <p>
   * When implementing a custom data source, pass {@link Selector#EMPTY} for the {@code selector}
   * parameter. Non-empty selectors are only meaningful for LaunchDarkly's own data sources.
   *
   * @param type          the type of the changeset
   * @param selector      the selector for this change; null is normalized to {@link Selector#EMPTY}
   * @param data          the data payload
   * @param environmentId the environment ID, or null if not available
   * @param shouldPersist true if the data should be persisted to persistent stores
   */
  public ChangeSet(ChangeSetType type, Selector selector, T data, String environmentId, boolean shouldPersist) {
    this.type = type;
    this.selector = selector != null ? selector : Selector.EMPTY;
    this.data = data;
    this.environmentId = environmentId;
    this.shouldPersist = shouldPersist;
  }

  /**
   * Returns the type of the changeset.
   *
   * @return the changeset type
   */
  public ChangeSetType getType() {
    return type;
  }

  /**
   * Returns the selector for this change. Will not be null; may be {@link Selector#EMPTY}.
   *
   * @return the selector
   */
  public Selector getSelector() {
    return selector;
  }

  /**
   * Returns the environment ID associated with the change, or null if not available.
   *
   * @return the environment ID, or null
   */
  public String getEnvironmentId() {
    return environmentId;
  }

  /**
   * Returns the data payload for this changeset.
   *
   * @return the data
   */
  public T getData() {
    return data;
  }

  /**
   * Returns whether this data should be persisted to persistent stores.
   *
   * @return true if the data should be persisted, false otherwise
   */
  public boolean shouldPersist() {
    return shouldPersist;
  }

  @Override
  public boolean equals(Object o) {
    if (o instanceof ChangeSet<?>) {
      ChangeSet<?> other = (ChangeSet<?>) o;
      return type == other.type
          && shouldPersist == other.shouldPersist
          && Objects.equals(selector, other.selector)
          && Objects.equals(environmentId, other.environmentId)
          && Objects.equals(data, other.data);
    }
    return false;
  }

  @Override
  public int hashCode() {
    return Objects.hash(type, selector, environmentId, data, shouldPersist);
  }

  @Override
  public String toString() {
    return "ChangeSet(" + type + "," + selector + "," + environmentId + "," + data + "," + shouldPersist + ")";
  }
}
