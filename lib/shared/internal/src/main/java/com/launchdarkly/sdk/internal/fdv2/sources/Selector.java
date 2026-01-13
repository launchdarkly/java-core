package com.launchdarkly.sdk.internal.fdv2.sources;

/**
 * A selector can either be empty or it can contain state and a version.
 */
public final class Selector {
  private final boolean isEmpty;
  private final int version;
  private final String state;

  private Selector(int version, String state, boolean isEmpty) {
    this.version = version;
    this.state = state;
    this.isEmpty = isEmpty;
  }

  /**
   * If true, then this selector is empty. An empty selector cannot be used as a basis for a data source.
   *
   * @return whether the selector is empty
   */
  public boolean isEmpty() {
    return isEmpty;
  }

  /**
   * The version of the data associated with this selector.
   *
   * @return the version
   */
  public int getVersion() {
    return version;
  }

  /**
   * The state associated with the payload.
   *
   * @return the state identifier, or null if empty
   */
  public String getState() {
    return state;
  }

  static Selector empty() {
    return new Selector(0, null, true);
  }

  static Selector make(int version, String state) {
    return new Selector(version, state, false);
  }

  /**
   * An empty selector instance.
   */
  public static final Selector EMPTY = empty();
}


