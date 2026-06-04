package com.launchdarkly.sdk.server.ai.datamodel;

import com.launchdarkly.sdk.ArrayBuilder;
import com.launchdarkly.sdk.LDValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Configuration describing the judges attached to an AI Config for automatic online evaluation.
 * <p>
 * Each entry pairs a judge configuration key with the sampling rate at which that judge should be
 * invoked.
 */
public final class JudgeConfiguration {
  /**
   * Configuration for a single judge attachment.
   */
  public static final class Judge {
    private final String key;
    private final double samplingRate;

    /**
     * Creates a judge attachment.
     *
     * @param key the judge configuration key
     * @param samplingRate the sampling rate, between {@code 0.0} and {@code 1.0}
     */
    public Judge(String key, double samplingRate) {
      this.key = key;
      this.samplingRate = samplingRate;
    }

    /**
     * Returns the judge configuration key.
     *
     * @return the key
     */
    public String getKey() {
      return key;
    }

    /**
     * Returns the sampling rate.
     *
     * @return the sampling rate
     */
    public double getSamplingRate() {
      return samplingRate;
    }

    /**
     * Renders this judge attachment as an {@link LDValue} object.
     *
     * @return the JSON representation
     */
    public LDValue toLDValue() {
      return LDValue.buildObject()
          .put("key", key)
          .put("samplingRate", samplingRate)
          .build();
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
      return Double.compare(samplingRate, other.samplingRate) == 0 && Objects.equals(key, other.key);
    }

    @Override
    public int hashCode() {
      return Objects.hash(key, samplingRate);
    }

    @Override
    public String toString() {
      return "Judge{key=" + key + ", samplingRate=" + samplingRate + "}";
    }
  }

  private final List<Judge> judges;

  /**
   * Creates a judge configuration.
   *
   * @param judges the judges to attach
   */
  public JudgeConfiguration(List<Judge> judges) {
    this.judges = Collections.unmodifiableList(new ArrayList<>(judges));
  }

  /**
   * Returns the judges in this configuration.
   *
   * @return an unmodifiable list of judges
   */
  public List<Judge> getJudges() {
    return judges;
  }

  /**
   * Renders this judge configuration as an {@link LDValue} object.
   *
   * @return the JSON representation
   */
  public LDValue toLDValue() {
    ArrayBuilder array = LDValue.buildArray();
    for (Judge judge : judges) {
      array.add(judge.toLDValue());
    }
    return LDValue.buildObject().put("judges", array.build()).build();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof JudgeConfiguration)) {
      return false;
    }
    return Objects.equals(judges, ((JudgeConfiguration) o).judges);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(judges);
  }

  @Override
  public String toString() {
    return "JudgeConfiguration{judges=" + judges + "}";
  }
}
