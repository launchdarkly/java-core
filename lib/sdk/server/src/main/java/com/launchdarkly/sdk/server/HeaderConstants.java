package com.launchdarkly.sdk.server;

enum HeaderConstants {
  ENVIRONMENT_ID("x-ld-envid"),
  FDV1_FALLBACK("x-ld-fd-fallback"),
  FDV1_FALLBACK_TTL("x-ld-fd-fallback-ttl");

  private final String headerName;

  HeaderConstants(String headerName) {
    this.headerName = headerName;
  }

  public String getHeaderName() {
    return headerName;
  }
}
