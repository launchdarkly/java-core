package com.launchdarkly.sdk.server.ai.datamodel;

import java.util.Objects;

/**
 * A single prompt message in an AI Config, consisting of a {@link Role} and string content.
 * <p>
 * Instances are immutable.
 */
public final class LDMessage {
  /**
   * The role of a {@link LDMessage}.
   */
  public enum Role {
    /**
     * A system message, typically used to set behavior or context.
     */
    SYSTEM("system"),

    /**
     * A message authored by the end user.
     */
    USER("user"),

    /**
     * A message authored by the assistant (model).
     */
    ASSISTANT("assistant");

    private final String wireValue;

    Role(String wireValue) {
      this.wireValue = wireValue;
    }

    /**
     * Returns the string used to represent this role in the JSON protocol.
     *
     * @return the wire representation (for example {@code "system"})
     */
    public String getWireValue() {
      return wireValue;
    }

    /**
     * Resolves a wire string to a role.
     *
     * @param value the wire value, such as {@code "user"}; may be {@code null}
     * @return the matching role, or {@code null} if the value is {@code null} or unrecognized
     */
    public static Role fromWireValue(String value) {
      if (value == null) {
        return null;
      }
      for (Role role : values()) {
        if (role.wireValue.equals(value)) {
          return role;
        }
      }
      return null;
    }
  }

  private final Role role;
  private final String content;

  /**
   * Constructs a message.
   *
   * @param role the role of the message; must not be {@code null}
   * @param content the message content; must not be {@code null}
   * @throws NullPointerException if {@code role} or {@code content} is {@code null}
   */
  public LDMessage(Role role, String content) {
    this.role = Objects.requireNonNull(role, "role");
    this.content = Objects.requireNonNull(content, "content");
  }

  /**
   * Returns the role of this message.
   *
   * @return the role, never {@code null}
   */
  public Role getRole() {
    return role;
  }

  /**
   * Returns the content of this message.
   *
   * @return the content, never {@code null}
   */
  public String getContent() {
    return content;
  }

  /**
   * Returns a copy of this message with the given content, preserving the role.
   * <p>
   * Used by the interpolation layer to produce a rendered message without mutating the original.
   *
   * @param newContent the replacement content; must not be {@code null}
   * @return a new {@link LDMessage}
   */
  public LDMessage withContent(String newContent) {
    return new LDMessage(role, newContent);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof LDMessage)) {
      return false;
    }
    LDMessage other = (LDMessage) o;
    return role == other.role && content.equals(other.content);
  }

  @Override
  public int hashCode() {
    return Objects.hash(role, content);
  }

  @Override
  public String toString() {
    return "LDMessage{role=" + role + ", content=" + content + '}';
  }
}
