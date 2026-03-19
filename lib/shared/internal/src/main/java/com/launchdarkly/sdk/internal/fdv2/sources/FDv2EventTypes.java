package com.launchdarkly.sdk.internal.fdv2.sources;

/**
 * Types of events that FDv2 can receive.
 */
public final class FDv2EventTypes {
  private FDv2EventTypes() {}

  public static final String SERVER_INTENT = "server-intent";
  public static final String PUT_OBJECT = "put-object";
  public static final String DELETE_OBJECT = "delete-object";
  public static final String ERROR = "error";
  public static final String GOODBYE = "goodbye";
  public static final String HEARTBEAT = "heartbeat";
  public static final String PAYLOAD_TRANSFERRED = "payload-transferred";
}


