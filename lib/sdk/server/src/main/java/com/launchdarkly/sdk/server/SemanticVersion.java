package com.launchdarkly.sdk.server;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simple implementation of semantic version parsing and comparison according to the Semantic
 * Versions 2.0.0 standard (http://semver.org).
 */
final class SemanticVersion implements Comparable<SemanticVersion> {

  private static Pattern VERSION_REGEX = Pattern.compile(
      "^(?<major>0|[1-9]\\d*)(\\.(?<minor>0|[1-9]\\d*))?(\\.(?<patch>0|[1-9]\\d*))?" +
      "(\\-(?<prerel>[0-9A-Za-z\\-\\.]+))?(\\+(?<build>[0-9A-Za-z\\-\\.]+))?$");
  
  @SuppressWarnings("serial")
  public static class InvalidVersionException extends Exception {
    public InvalidVersionException(String message) {
      super(message);
    }
  }
  
  private final int major;
  private final int minor;
  private final int patch;
  private final String prerelease;
  private final String[] prereleaseComponents;
  private final String build;
  
  public SemanticVersion(int major, int minor, int patch, String prerelease, String build) {
    this.major = major;
    this.minor = minor;
    this.patch = patch;
    this.prerelease = prerelease;
    this.prereleaseComponents = prerelease == null ? null : prerelease.split("\\.");
    this.build = build;
  }
  
  public int getMajor() {
    return major;
  }

  public int getMinor() {
    return minor;
  }

  public int getPatch() {
    return patch;
  }

  public String getPrerelease() {
    return prerelease;
  }

  public String getBuild() {
    return build;
  }

  /**
   * Attempts to parse a string as a semantic version according to the Semver 2.0.0 specification.
   * @param input the input string
   * @return a SemanticVersion instance
   * @throws InvalidVersionException if the version could not be parsed
   */
  public static SemanticVersion parse(String input) throws InvalidVersionException {
    return parse(input, false);
  }
  
  /**
   * Attempts to parse a string as a semantic version according to the Semver 2.0.0 specification, except that
   * the minor and patch versions may optionally be omitted.
   * @param input the input string
   * @param allowMissingMinorAndPatch true if the parser should tolerate the absence of a minor and/or
   *   patch version; if absent, they will be treated as zero
   * @return a SemanticVersion instance
   * @throws InvalidVersionException if the version could not be parsed
   */
  public static SemanticVersion parse(String input, boolean allowMissingMinorAndPatch) throws InvalidVersionException {
    Matcher matcher = VERSION_REGEX.matcher(input);
    if (!matcher.matches()) {
      throw new InvalidVersionException("Invalid semantic version");
    }
    int major, minor, patch;
    try {
      major = Integer.parseInt(matcher.group("major"));
      if (!allowMissingMinorAndPatch) {
        if (matcher.group("minor") == null || matcher.group("patch") == null) {
          throw new InvalidVersionException("Invalid semantic version");
        }
      }
      minor = matcher.group("minor") == null ? 0 : Integer.parseInt(matcher.group("minor"));
      patch = matcher.group("patch") == null ? 0 : Integer.parseInt(matcher.group("patch"));
    } catch (NumberFormatException e) {
      // COVERAGE: This should be impossible, because our regex should only match if these strings are numeric.
      throw new InvalidVersionException("Invalid semantic version");
    }
    String prerelease = matcher.group("prerel");
    String build = matcher.group("build");
    return new SemanticVersion(major, minor, patch, prerelease, build);
  }
  
  @Override
  public int compareTo(SemanticVersion other) {
    return comparePrecedence(other);
  }
  
  /**
   * Compares this object with another SemanticVersion according to Semver 2.0.0 precedence rules.
   * @param other another SemanticVersion
   * @return 0 if equal, -1 if the current object has lower precedence, or 1 if the current object has higher precedence
   */
  public int comparePrecedence(SemanticVersion other) {
    if (other == null) {
      return 1;
    }
    if (major != other.major) {
      return Integer.compare(major, other.major);
    }
    if (minor != other.minor) {
      return Integer.compare(minor, other.minor);
    }
    if (patch != other.patch) {
      return Integer.compare(patch, other.patch);
    }
    if (prerelease == null && other.prerelease == null) {
      return 0;
    }
    // *no* prerelease component always has higher precedence than *any* prerelease component
    if (prerelease == null) {
      return 1;
    }
    if (other.prerelease == null) {
      return -1;
    }
    return compareIdentifiers(prereleaseComponents, other.prereleaseComponents);
  }
  
  private int compareIdentifiers(String[] ids1, String[] ids2) {
    for (int i = 0; ; i++) {
      if (i >= ids1.length)
      {
          // x.y is always less than x.y.z
          return (i >= ids2.length) ? 0 : -1;
      }
      if (i >= ids2.length)
      {
          return 1;
      }
      // each sub-identifier is compared numerically if both are numeric; if both are non-numeric,
      // they're compared as strings; otherwise, the numeric one is the lesser one
      int n1 = 0, n2 = 0, d;
      boolean isNum1, isNum2;
      try {
        n1 = Integer.parseInt(ids1[i]);
        isNum1 = true;
      } catch (NumberFormatException e) {
        isNum1 = false;
      }
      try {
        n2 = Integer.parseInt(ids2[i]);
        isNum2 = true;
      } catch (NumberFormatException e) {
        isNum2 = false;
      }
      if (isNum1 && isNum2)
      {
          d = Integer.compare(n1, n2);
      }
      else
      {
          d = isNum1 ? -1 : (isNum2 ? 1 : ids1[i].compareTo(ids2[i]));
      }
      if (d != 0)
      {
          return d;
      }
    }
  }
}
