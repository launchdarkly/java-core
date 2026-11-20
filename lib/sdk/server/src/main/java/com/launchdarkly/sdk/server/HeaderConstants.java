package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.server.datasources.FDv1Fallback;

import java.time.Duration;

enum HeaderConstants {
  ENVIRONMENT_ID("x-ld-envid"),
  FDV1_FALLBACK("x-ld-fd-fallback"),
  FDV1_FALLBACK_TTL("x-ld-fd-fallback-ttl");

  private final String headerName;

  HeaderConstants(String headerName) {
    this.headerName = headerName;
  }

  static FDv1Fallback getFallback(String directive, String value) {
    if (!"true".equalsIgnoreCase(directive)) {
      return null;
    }
    Duration ttl = null;
    try {
      ttl = value == null ? null : Duration.ofSeconds(Long.parseLong(value));
    } catch (NumberFormatException e) {
      ttl = null;
    }
    return new FDv1Fallback(ttl);
  }

  public String getHeaderName() {
    return headerName;
  }
}
