package com.launchdarkly.sdk.server.ai.datamodel;

import com.launchdarkly.sdk.LDValue;

import java.util.Objects;

/**
 * A single message used to compose a prompt for an AI Config.
 * <p>
 * A message pairs a {@code role} (one of {@code system}, {@code user}, or {@code assistant}) with
 * its {@code content}. When a message is delivered as part of an AI Config, its content is
 * interpolated using Mustache templating before the message is returned to the caller.
 */
public final class LDMessage {
  /** The {@code system} role. */
  public static final String ROLE_SYSTEM = "system";
  /** The {@code user} role. */
  public static final String ROLE_USER = "user";
  /** The {@code assistant} role. */
  public static final String ROLE_ASSISTANT = "assistant";

  private final String role;
  private final String content;

  /**
   * Creates a message.
   *
   * @param role the role of the message, typically one of {@link #ROLE_SYSTEM}, {@link #ROLE_USER},
   *     or {@link #ROLE_ASSISTANT}
   * @param content the message content
   */
  public LDMessage(String role, String content) {
    this.role = role;
    this.content = content;
  }

  /**
   * Creates a {@code system} message.
   *
   * @param content the message content
   * @return the message
   */
  public static LDMessage system(String content) {
    return new LDMessage(ROLE_SYSTEM, content);
  }

  /**
   * Creates a {@code user} message.
   *
   * @param content the message content
   * @return the message
   */
  public static LDMessage user(String content) {
    return new LDMessage(ROLE_USER, content);
  }

  /**
   * Creates an {@code assistant} message.
   *
   * @param content the message content
   * @return the message
   */
  public static LDMessage assistant(String content) {
    return new LDMessage(ROLE_ASSISTANT, content);
  }

  /**
   * Returns the role of the message.
   *
   * @return the role
   */
  public String getRole() {
    return role;
  }

  /**
   * Returns the message content.
   *
   * @return the content
   */
  public String getContent() {
    return content;
  }

  /**
   * Renders this message as an {@link LDValue} object.
   *
   * @return the JSON representation
   */
  public LDValue toLDValue() {
    return LDValue.buildObject()
        .put("role", role)
        .put("content", content)
        .build();
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
    return Objects.equals(role, other.role) && Objects.equals(content, other.content);
  }

  @Override
  public int hashCode() {
    return Objects.hash(role, content);
  }

  @Override
  public String toString() {
    return "LDMessage{role=" + role + ", content=" + content + "}";
  }
}
