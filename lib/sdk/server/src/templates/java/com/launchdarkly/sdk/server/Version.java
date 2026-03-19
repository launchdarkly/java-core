package com.launchdarkly.sdk.server;

abstract class Version {
  private Version() {}
  
  // This constant is updated automatically by our Gradle script during a release, if the project version has changed
  // x-release-please-start-version
  static final String SDK_VERSION = "@VERSION@";
  // x-release-please-end
}
