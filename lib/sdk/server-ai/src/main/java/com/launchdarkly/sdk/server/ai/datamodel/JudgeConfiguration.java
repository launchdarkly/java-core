package com.launchdarkly.sdk.server.ai.datamodel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Configuration referencing the judges that may evaluate an AI Config.
 * <p>
 * This is parsed from the {@code judgeConfiguration} field of a flag variation and is visible on
 * completion and agent configs. In v1.0 judges are invoked manually; the SDK does not auto-attach
 * them. Instances are immutable.
 */
public final class JudgeConfiguration {
  /**
   * Configuration for a single judge attachment: which judge AI Config to use and how frequently
   * to sample it.
   * <p>
   * Instances are immutable.
   */
  public static final class Judge {
    private final String key;
    private final double samplingRate;

    /**
     * Constructs a judge attachment.
     *
     * @param key the key of the judge AI Config; must not be {@code null}
     * @param samplingRate the sampling rate, nominally in the range {@code 0.0}–{@code 1.0}
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public Judge(String key, double samplingRate) {
      this.key = Objects.requireNonNull(key, "key");
      this.samplingRate = samplingRate;
    }

    /**
     * Returns the key of the judge AI Config.
     *
     * @return the judge key, never {@code null}
     */
    public String getKey() {
      return key;
    }

    /**
     * Returns the configured sampling rate.
     *
     * @return the sampling rate
     */
    public double getSamplingRate() {
      return samplingRate;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Judge)) {
        return false;
      }
      Judge other = (Judge) o;
      return Double.compare(samplingRate, other.samplingRate) == 0 && key.equals(other.key);
    }

    @Override
    public int hashCode() {
      return Objects.hash(key, samplingRate);
    }

    @Override
    public String toString() {
      return "Judge{key=" + key + ", samplingRate=" + samplingRate + '}';
    }
  }

  private final List<Judge> judges;

  /**
   * Constructs a judge configuration.
   *
   * @param judges the judge attachments; may be {@code null}, treated as empty
   */
  public JudgeConfiguration(List<Judge> judges) {
    this.judges = judges == null
        ? Collections.<Judge>emptyList()
        : Collections.unmodifiableList(new ArrayList<>(judges));
  }

  /**
   * Returns the configured judge attachments as an unmodifiable list.
   *
   * @return the judges; never {@code null} (empty when none were specified)
   */
  public List<Judge> getJudges() {
    return judges;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof JudgeConfiguration)) {
      return false;
    }
    return judges.equals(((JudgeConfiguration) o).judges);
  }

  @Override
  public int hashCode() {
    return judges.hashCode();
  }

  @Override
  public String toString() {
    return "JudgeConfiguration{judges=" + judges + '}';
  }
}
