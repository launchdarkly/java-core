package com.launchdarkly.sdk.internal.fdv2.sources;

import com.google.gson.JsonElement;
import com.launchdarkly.sdk.fdv2.Selector;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Change tracking structures for FDv2.
 */
public final class FDv2ChangeSet {
  /**
   * Represents the type of change operation.
   */
  public enum FDv2ChangeType {
    /**
     * Indicates an upsert operation (insert or update).
     */
    PUT,

    /**
     * Indicates a delete operation.
     */
    DELETE
  }

  /**
   * Represents the type of changeset.
   */
  public enum FDv2ChangeSetType {
    /**
     * Changeset represents a full payload to use as a basis.
     */
    FULL,

    /**
     * Changeset represents a partial payload to be applied to a basis.
     */
    PARTIAL,

    /**
     * A changeset which indicates that no changes should be made.
     */
    NONE
  }

  /**
   * Represents a single change to a data object.
   */
  public static final class FDv2Change {
    private final FDv2ChangeType type;
    private final String kind;
    private final String key;
    private final int version;
    private final JsonElement object;

    /**
     * Constructs a new Change.
     *
     * @param type the type of change operation
     * @param kind the kind of object being changed
     * @param key the key identifying the object
     * @param version the version of the change
     * @param object the raw JSON representing the object data (required for put operations)
     */
    public FDv2Change(FDv2ChangeType type, String kind, String key, int version, JsonElement object) {
      this.type = Objects.requireNonNull(type, "type");
      this.kind = Objects.requireNonNull(kind, "kind");
      this.key = Objects.requireNonNull(key, "key");
      this.version = version;
      this.object = object;
    }

    public FDv2ChangeType getType() {
      return type;
    }

    public String getKind() {
      return kind;
    }

    public String getKey() {
      return key;
    }

    public int getVersion() {
      return version;
    }

    /**
     * The raw JSON string representing the object data (only present for Put operations).
     *
     * @return the raw JSON element representing the object data
     */
    public JsonElement getObject() {
      return object;
    }
  }

  private final FDv2ChangeSetType type;
  private final List<FDv2Change> changes;
  private final Selector selector;

  /**
   * Constructs a new ChangeSet.
   *
   * @param type the type of the changeset
   * @param changes the list of changes (required)
   * @param selector the selector for this changeset
   */
  public FDv2ChangeSet(FDv2ChangeSetType type, List<FDv2Change> changes, Selector selector) {
    this.type = Objects.requireNonNull(type, "type");
    this.changes = Collections.unmodifiableList(Objects.requireNonNull(changes, "changes"));
    this.selector = selector;
  }

  /**
   * The intent code indicating how the server intends to transfer data.
   *
   * @return the type of changeset
   */
  public FDv2ChangeSetType getType() {
    return type;
  }

  /**
   * The list of changes in this changeset. May be empty if there are no changes.
   *
   * @return the list of changes in this changeset
   */
  public List<FDv2Change> getChanges() {
    return changes;
  }

  /**
   * The selector (version identifier) for this changeset.
   *
   * @return the selector for this changeset
   */
  public Selector getSelector() {
    return selector;
  }

  /**
   * An empty changeset that indicates no changes are required.
   */
  public static final FDv2ChangeSet NONE = new FDv2ChangeSet(
      FDv2ChangeSetType.NONE,
      Collections.emptyList(),
      Selector.EMPTY
  );
}


