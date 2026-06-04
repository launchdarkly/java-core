package com.launchdarkly.sdk.server.ai.tracking;

import java.util.Objects;

/**
 * Token usage reported by an AI provider for a single AI run.
 */
public final class TokenUsage {
  private final int total;
  private final int input;
  private final int output;

  /**
   * Creates a token usage record.
   *
   * @param total the total number of tokens used
   * @param input the number of tokens in the input (prompt)
   * @param output the number of tokens in the output (completion)
   */
  public TokenUsage(int total, int input, int output) {
    this.total = total;
    this.input = input;
    this.output = output;
  }

  /**
   * Returns the total number of tokens used.
   *
   * @return the total token count
   */
  public int getTotal() {
    return total;
  }

  /**
   * Returns the number of tokens in the input.
   *
   * @return the input token count
   */
  public int getInput() {
    return input;
  }

  /**
   * Returns the number of tokens in the output.
   *
   * @return the output token count
   */
  public int getOutput() {
    return output;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TokenUsage)) {
      return false;
    }
    TokenUsage other = (TokenUsage) o;
    return total == other.total && input == other.input && output == other.output;
  }

  @Override
  public int hashCode() {
    return Objects.hash(total, input, output);
  }

  @Override
  public String toString() {
    return "TokenUsage{total=" + total + ", input=" + input + ", output=" + output + "}";
  }
}
