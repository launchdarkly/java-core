package com.launchdarkly.sdk.fdv2;

/**
 * Indicates the type of a change set applied to a data store.
 */
public enum ChangeSetType {
  /**
   * Represents a full store update which replaces all data currently in the store.
   */
  Full,

  /**
   * Represents an incremental set of changes to be applied to the existing data in the store.
   */
  Partial,

  /**
   * Indicates that there are no changes; the changeset may still carry a selector to store.
   */
  None
}
