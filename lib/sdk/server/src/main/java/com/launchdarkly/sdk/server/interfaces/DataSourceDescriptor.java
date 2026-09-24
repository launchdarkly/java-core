package com.launchdarkly.sdk.server.interfaces;

import java.util.Objects;

/**
 * Identifies a data source component: an initializer, a synchronizer, or the single data source of
 * an SDK without a data system.
 * <p>
 * The protocol and the transport are fixed by the component type. The name is the default name of
 * the component type, or the name that the application configured. Each field can be absent. A
 * descriptor with no field set is empty; see {@link #isDefined()}.
 *
 * @since 7.18.0
 */
public final class DataSourceDescriptor {
  /**
   * The protocol a component speaks.
   */
  public enum Protocol {
    /**
     * The FDv1 protocol.
     */
    FDV1("fdv1"),
    /**
     * The FDv2 protocol.
     */
    FDV2("fdv2");

    private final String value;

    Protocol(String value) {
      this.value = value;
    }

    /**
     * @return the lowercase protocol name used in telemetry
     */
    public String getValue() {
      return value;
    }
  }

  /**
   * The transport a component uses.
   */
  public enum Transport {
    /**
     * A streaming connection.
     */
    STREAMING("streaming"),
    /**
     * Periodic polling.
     */
    POLLING("polling"),
    /**
     * A local file.
     */
    FILE("file");

    private final String value;

    Transport(String value) {
      this.value = value;
    }

    /**
     * @return the lowercase transport name used in telemetry
     */
    public String getValue() {
      return value;
    }
  }

  private static final DataSourceDescriptor EMPTY = new DataSourceDescriptor(null, null, null);

  private final Protocol protocol;
  private final Transport transport;
  private final String name;

  private DataSourceDescriptor(Protocol protocol, Transport transport, String name) {
    this.protocol = protocol;
    this.transport = transport;
    this.name = name == null || name.isEmpty() ? null : name;
  }

  /**
   * Creates a descriptor. Any field can be null.
   *
   * @param protocol the protocol, or null
   * @param transport the transport, or null
   * @param name the component name, or null
   * @return the descriptor
   */
  public static DataSourceDescriptor of(Protocol protocol, Transport transport, String name) {
    return new DataSourceDescriptor(protocol, transport, name);
  }

  /**
   * Creates a descriptor that has only a name.
   *
   * @param name the component name
   * @return the descriptor
   */
  public static DataSourceDescriptor named(String name) {
    return new DataSourceDescriptor(null, null, name);
  }

  /**
   * @return a descriptor with no field set
   */
  public static DataSourceDescriptor empty() {
    return EMPTY;
  }

  /**
   * @return the protocol, or null if not provided
   */
  public Protocol getProtocol() {
    return protocol;
  }

  /**
   * @return the transport, or null if not provided
   */
  public Transport getTransport() {
    return transport;
  }

  /**
   * @return the component name, or null if not provided
   */
  public String getName() {
    return name;
  }

  /**
   * @return true if at least one field is set
   */
  public boolean isDefined() {
    return protocol != null || transport != null || name != null;
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof DataSourceDescriptor)) {
      return false;
    }
    DataSourceDescriptor o = (DataSourceDescriptor) other;
    return protocol == o.protocol && transport == o.transport && Objects.equals(name, o.name);
  }

  @Override
  public int hashCode() {
    return Objects.hash(protocol, transport, name);
  }

  @Override
  public String toString() {
    return "DataSourceDescriptor(" + protocol + "," + transport + "," + name + ")";
  }
}
