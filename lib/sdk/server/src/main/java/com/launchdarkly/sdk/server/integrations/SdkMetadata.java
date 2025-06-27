package com.launchdarkly.sdk.server.integrations;

/**
 * Metadata about the LaunchDarkly SDK.
 */
public final class SdkMetadata {
  private final String name;
  private final String version;
  private final String wrapperName;
  private final String wrapperVersion;

  public SdkMetadata(String name, String version) {
    this(name, version, null, null);
  }

  public SdkMetadata(String name, String version, String wrapperName, String wrapperVersion) {
    this.name = name;
    this.version = version;
    this.wrapperName = wrapperName;
    this.wrapperVersion = wrapperVersion;
  }

  /**
   * @return name of the SDK for informational purposes such as logging
   */
  public String getName() {
    return name;
  }

  /**
   * @return version of the SDK for informational purposes such as logging
   */
  public String getVersion() {
    return version;
  }

  /**
   * @return name of the wrapper if this is a wrapper SDK
   */
  public String getWrapperName() {
    return wrapperName;
  }

  /**
   * @return version of the wrapper if this is a wrapper SDK
   */
  public String getWrapperVersion() {
    return wrapperVersion;
  }
}
