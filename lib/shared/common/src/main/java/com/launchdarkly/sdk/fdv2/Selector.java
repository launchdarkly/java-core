package com.launchdarkly.sdk.fdv2;

/**
 * Identifies a specific version of data in the LaunchDarkly backend, used to request incremental
 * updates from a known point.
 * <p>
 * A selector is either empty ({@link #EMPTY}) or contains a version number and state string that
 * were provided by a LaunchDarkly data source. Empty selectors signal that the client has no
 * existing data and requires a full payload.
 * <p>
 * <strong>For SDK consumers implementing custom data sources:</strong> you should always use
 * {@link #EMPTY} when constructing a {@link ChangeSet}. Non-empty selectors are set by
 * LaunchDarkly's own data sources based on state received from the LaunchDarkly backend, and
 * are not meaningful when constructed externally.
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
   * If true, then this selector is empty. An empty selector cannot be used as a basis for
   * requesting incremental updates from a data source.
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

  /**
   * Creates a new Selector with the given version and state.
   * <p>
   * <strong>This method is intended for use by LaunchDarkly data sources only.</strong>
   * Custom data source implementations should use {@link #EMPTY} instead.
   *
   * @param version the version number
   * @param state the state identifier
   * @return a new Selector instance
   */
  public static Selector make(int version, String state) {
    return new Selector(version, state, false);
  }

  /**
   * An empty selector instance. Custom data source implementations should always use this
   * value when constructing a {@link ChangeSet}.
   */
  public static final Selector EMPTY = empty();
}
