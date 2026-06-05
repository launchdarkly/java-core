package com.launchdarkly.sdk.server.ai.datamodel;

/**
 * The mode of an AI Config, as carried by the {@code _ldMeta.mode} field of a flag variation.
 * <p>
 * The mode determines which kind of configuration a variation represents and which retrieval
 * method on the client it is valid for.
 */
public enum AIConfigMode {
  /**
   * A completion (chat/prompt) configuration. This is the default when no mode is present.
   */
  COMPLETION("completion"),

  /**
   * An agent configuration, which carries {@code instructions} instead of {@code messages}.
   */
  AGENT("agent"),

  /**
   * A judge configuration, used to evaluate the output of another configuration.
   */
  JUDGE("judge");

  private final String wireValue;

  AIConfigMode(String wireValue) {
    this.wireValue = wireValue;
  }

  /**
   * Returns the string used to represent this mode in the JSON protocol.
   *
   * @return the wire representation (for example {@code "completion"})
   */
  public String getWireValue() {
    return wireValue;
  }

  /**
   * Resolves a wire string to a mode.
   *
   * @param value the wire value, such as {@code "agent"}; may be {@code null}
   * @return the matching mode, or {@code null} if the value is {@code null} or unrecognized
   */
  public static AIConfigMode fromWireValue(String value) {
    if (value == null) {
      return null;
    }
    for (AIConfigMode mode : values()) {
      if (mode.wireValue.equals(value)) {
        return mode;
      }
    }
    return null;
  }
}
