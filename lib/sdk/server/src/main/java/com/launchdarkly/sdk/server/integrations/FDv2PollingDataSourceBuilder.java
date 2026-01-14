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
 * Contains methods for configuring the polling data source.
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
 *             // DataSystemComponents.polling() returns an instance of this builder.
 *             .initializers(DataSystemComponents.polling()
 *                 .pollInterval(Duration.ofMinutes(10)))
 *             .synchronizers(DataSystemComponents.streaming(), DataSystemComponents.polling())
 *             .fDv1FallbackSynchronizer(DataSystemComponents.fDv1Polling()));
 * </code></pre>
 */
public final class FDv2PollingDataSourceBuilder implements ComponentConfigurer<DataSource>, DiagnosticDescription {
  /**
   * The default value for {@link #pollInterval(Duration)}: 30 seconds.
   */
  public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(30);

  Duration pollInterval = DEFAULT_POLL_INTERVAL;

  private ServiceEndpoints serviceEndpointsOverride;

  /**
   * Sets the interval at which the SDK will poll for feature flag updates.
   * <p>
   * The default and minimum value is {@link #DEFAULT_POLL_INTERVAL}. Values less than this will
   * be set to the default.
   * </p>
   * 
   * @param pollInterval the polling interval
   * @return the builder
   */
  public FDv2PollingDataSourceBuilder pollInterval(Duration pollInterval) {
    this.pollInterval = pollInterval != null && pollInterval.compareTo(DEFAULT_POLL_INTERVAL) >= 0
        ? pollInterval
        : DEFAULT_POLL_INTERVAL;
    return this;
  }

  /**
   * Exposed internally for testing.
   * 
   * @param pollInterval the polling interval
   * @return the builder
   */
  FDv2PollingDataSourceBuilder pollIntervalNoMinimum(Duration pollInterval) {
    this.pollInterval = pollInterval;
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
  public FDv2PollingDataSourceBuilder serviceEndpointsOverride(ServiceEndpointsBuilder serviceEndpointsOverride) {
    this.serviceEndpointsOverride = serviceEndpointsOverride.createServiceEndpoints();
    return this;
  }

  @Override
  public DataSource build(ClientContext context) {
    ServiceEndpoints endpoints = serviceEndpointsOverride != null
        ? serviceEndpointsOverride
        : context.getServiceEndpoints();
    URI configuredBaseUri = StandardEndpoints.selectBaseUri(
        endpoints.getPollingBaseUri(),
        StandardEndpoints.DEFAULT_POLLING_BASE_URI,
        "Polling",
        context.getBaseLogger());

    // TODO: Implement FDv2PollingRequestor
    // var requestor = new FDv2PollingRequestor(context, configuredBaseUri);
    
    // TODO: Implement FDv2PollingDataSource
    // return new FDv2PollingDataSource(
    //     context,
    //     context.getDataSourceUpdateSink(),
    //     requestor,
    //     pollInterval,
    //     () -> context.getSelectorSource() != null ? context.getSelectorSource().getSelector() : Selector.empty()
    // );
    
    // Placeholder - this will not compile until FDv2PollingDataSource is implemented
    throw new UnsupportedOperationException("FDv2PollingDataSource is not yet implemented");
  }

  @Override
  public LDValue describeConfiguration(ClientContext context) {
    ServiceEndpoints endpoints = serviceEndpointsOverride != null
        ? serviceEndpointsOverride
        : context.getServiceEndpoints();
    
    boolean customPollingBaseUri = StandardEndpoints.isCustomBaseUri(
        endpoints.getPollingBaseUri(), StandardEndpoints.DEFAULT_POLLING_BASE_URI);
    
    return LDValue.buildObject()
        .put(DiagnosticConfigProperty.STREAMING_DISABLED.name, true)
        .put(DiagnosticConfigProperty.CUSTOM_BASE_URI.name, customPollingBaseUri)
        .put(DiagnosticConfigProperty.POLLING_INTERVAL_MILLIS.name, pollInterval.toMillis())
        .put(DiagnosticConfigProperty.USING_RELAY_DAEMON.name, false)
        .build();
  }
}

