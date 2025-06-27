package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.sdk.server.interfaces.ApplicationInfo;

/**
 * Metadata about the environment that flag evaluations or other functionalities are being performed in.
 */
public final class EnvironmentMetadata {
  private final ApplicationInfo applicationInfo;
  private final SdkMetadata sdkMetadata;
  private final String sdkKey;

  /**
   * @param applicationInfo for the application this SDK is used in
   * @param sdkMetadata for the LaunchDarkly SDK
   * @param sdkKey for the key used to initialize the SDK client
   */
  public EnvironmentMetadata(ApplicationInfo applicationInfo, SdkMetadata sdkMetadata, String sdkKey) {
    this.applicationInfo = applicationInfo;
    this.sdkMetadata = sdkMetadata;
    this.sdkKey = sdkKey;
  }

  /**
   * @return the {@link ApplicationInfo} for the application this SDK is used in.
   */
  public ApplicationInfo getApplicationInfo() {
    return applicationInfo;
  }

  /**
   * @return the {@link SdkMetadata} for the LaunchDarkly SDK.
   */
  public SdkMetadata getSdkMetadata() {
    return sdkMetadata;
  }

  /**
   * @return the key used to initialize the SDK client
   */
  public String getSdkKey() {
    return sdkKey;
  }
}
