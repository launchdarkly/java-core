package com.launchdarkly.sdk.server;

import java.time.Duration;

enum HeaderConstants {
  ENVIRONMENT_ID("x-ld-envid"),
  FDV1_FALLBACK("x-ld-fd-fallback"),
  FDV1_FALLBACK_TTL("x-ld-fd-fallback-ttl");

  private final String headerName;

  HeaderConstants(String headerName) {
    this.headerName = headerName;
  }

  static Duration parseFallbackTTL(String value) {
    try {
      return value == null ? null : Duration.ofSeconds(Long.parseLong(value));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  public String getHeaderName() {
    return headerName;
  }
}
