package com.launchdarkly.sdk.server;

enum HeaderConstants {
  ENVIRONMENT_ID("x-ld-envid"),
  FDV1_FALLBACK("x-ld-fd-fallback");

  private final String headerName;

  HeaderConstants(String headerName) {
    this.headerName = headerName;
  }

  public String getHeaderName() {
    return headerName;
  }
}
