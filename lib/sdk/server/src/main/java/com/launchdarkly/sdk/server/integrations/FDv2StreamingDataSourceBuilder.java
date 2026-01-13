package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.internal.events.DiagnosticConfigProperty;
import com.launchdarkly.sdk.server.StandardEndpoints;
import com.launchdarkly.sdk.server.interfaces.ServiceEndpoints;
import com.launchdarkly.sdk.server.subsystems.ClientContext;
import com.launchdarkly.sdk.server.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.server.subsystems.DataSource;
import com.launchdarkly.sdk.server.subsystems.DiagnosticDescription;

import java.net.URI;
import java.time.Duration;

/**
 * Contains methods for configuring the streaming data source.
 * <p>
 * This class is not stable, and not subject to any backwards compatibility guarantees or semantic versioning.
 * It is in early access. If you want access to this feature please join the EAP. https://launchdarkly.com/docs/sdk/features/data-saving-mode
 * </p>
 * <p>
 * <b>Example:</b>
 * </p>
 * <pre><code>
 *     LDConfig config = new LDConfig.Builder("my-sdk-key")
 *         .dataSystem(Components.dataSystem().custom()
 *             .initializers(DataSystemComponents.polling())
 *             // DataSystemComponents.streaming() returns an instance of this builder.
 *             .synchronizers(DataSystemComponents.streaming()
 *                 .initialReconnectDelay(Duration.ofSeconds(5)), DataSystemComponents.polling())
 *             .fDv1FallbackSynchronizer(DataSystemComponents.fDv1Polling()));
 * </code></pre>
 */
public final class FDv2StreamingDataSourceBuilder implements ComponentConfigurer<DataSource>, DiagnosticDescription {
  /**
   * The default value for {@link #initialReconnectDelay(Duration)}: 1000 milliseconds.
   */
  public static final Duration DEFAULT_INITIAL_RECONNECT_DELAY = Duration.ofSeconds(1);

  private Duration initialReconnectDelay = DEFAULT_INITIAL_RECONNECT_DELAY;

  private ServiceEndpoints serviceEndpointsOverride;

  /**
   * Sets the initial reconnect delay for the streaming connection.
   * <p>
   * The streaming service uses a backoff algorithm (with jitter) every time the connection needs
   * to be reestablished. The delay for the first reconnection will start near this value, and then
   * increase exponentially for any subsequent connection failures.
   * </p>
   * <p>
   * The default value is {@link #DEFAULT_INITIAL_RECONNECT_DELAY}.
   * </p>
   * 
   * @param initialReconnectDelay the reconnect time base value
   * @return the builder
   */
  public FDv2StreamingDataSourceBuilder initialReconnectDelay(Duration initialReconnectDelay) {
    this.initialReconnectDelay = initialReconnectDelay != null ? initialReconnectDelay : DEFAULT_INITIAL_RECONNECT_DELAY;
    return this;
  }

  /**
   * Sets overrides for the service endpoints. In typical usage, the data source will use the commonly defined
   * service endpoints, but for cases where they need to be controlled at the source level, this method can
   * be used. This data source will only use the endpoints applicable to it.
   * 
   * @param serviceEndpointsOverride the service endpoints to override the base endpoints
   * @return the builder
   */
  public FDv2StreamingDataSourceBuilder serviceEndpointsOverride(ServiceEndpointsBuilder serviceEndpointsOverride) {
    this.serviceEndpointsOverride = serviceEndpointsOverride.createServiceEndpoints();
    return this;
  }

  @Override
  public DataSource build(ClientContext context) {
    ServiceEndpoints endpoints = serviceEndpointsOverride != null
        ? serviceEndpointsOverride
        : context.getServiceEndpoints();
    URI configuredBaseUri = StandardEndpoints.selectBaseUri(
        endpoints.getStreamingBaseUri(),
        StandardEndpoints.DEFAULT_STREAMING_BASE_URI,
        "Streaming",
        context.getBaseLogger());
    
    // TODO: Implement FDv2StreamingDataSource
    // return new FDv2StreamingDataSource(
    //     context,
    //     context.getDataSourceUpdateSink(),
    //     configuredBaseUri,
    //     initialReconnectDelay,
    //     () -> context.getSelectorSource() != null ? context.getSelectorSource().getSelector() : Selector.empty()
    // );
    
    // Placeholder - this will not compile until FDv2StreamingDataSource is implemented
    throw new UnsupportedOperationException("FDv2StreamingDataSource is not yet implemented");
  }

  @Override
  public LDValue describeConfiguration(ClientContext context) {
    ServiceEndpoints endpoints = serviceEndpointsOverride != null
        ? serviceEndpointsOverride
        : context.getServiceEndpoints();
    
    boolean customStreamingBaseUri = StandardEndpoints.isCustomBaseUri(
        endpoints.getStreamingBaseUri(), StandardEndpoints.DEFAULT_STREAMING_BASE_URI);
    boolean customPollingBaseUri = StandardEndpoints.isCustomBaseUri(
        endpoints.getPollingBaseUri(), StandardEndpoints.DEFAULT_POLLING_BASE_URI);
    
    return LDValue.buildObject()
        .put(DiagnosticConfigProperty.STREAMING_DISABLED.name, false)
        .put(DiagnosticConfigProperty.CUSTOM_BASE_URI.name, customPollingBaseUri)
        .put(DiagnosticConfigProperty.CUSTOM_STREAM_URI.name, customStreamingBaseUri)
        .put(DiagnosticConfigProperty.RECONNECT_TIME_MILLIS.name, initialReconnectDelay.toMillis())
        .put(DiagnosticConfigProperty.USING_RELAY_DAEMON.name, false)
        .build();
  }
}

