package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.internal.events.DiagnosticConfigProperty;
import com.launchdarkly.sdk.server.StandardEndpoints;
import com.launchdarkly.sdk.server.datasources.Synchronizer;
import com.launchdarkly.sdk.server.interfaces.ServiceEndpoints;
import com.launchdarkly.sdk.server.subsystems.ClientContext;
import com.launchdarkly.sdk.server.subsystems.DataSourceBuilder;
import com.launchdarkly.sdk.server.subsystems.DiagnosticDescription;

import java.net.URI;
import java.time.Duration;

/**
 * Contains methods for configuring the polling synchronizer.
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
 *             .initializers(DataSystemComponents.pollingInitializer())
 *             // DataSystemComponents.pollingSynchronizer() returns an instance of this builder.
 *             .synchronizers(DataSystemComponents.streamingSynchronizer(),
 *                 DataSystemComponents.pollingSynchronizer()
 *                     .pollInterval(Duration.ofMinutes(10)))
 *             .fDv1FallbackSynchronizer(DataSystemComponents.fDv1Polling()));
 * </code></pre>
 */
public abstract class FDv2PollingSynchronizerBuilder implements DataSourceBuilder<Synchronizer>, DiagnosticDescription {
  /**
   * The default value for {@link #pollInterval(Duration)}: 30 seconds.
   */
  public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(30);

  protected Duration pollInterval = DEFAULT_POLL_INTERVAL;

  protected ServiceEndpoints serviceEndpointsOverride;

  protected String payloadFilter;

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
  public FDv2PollingSynchronizerBuilder pollInterval(Duration pollInterval) {
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
  FDv2PollingSynchronizerBuilder pollIntervalNoMinimum(Duration pollInterval) {
    this.pollInterval = pollInterval;
    return this;
  }

  /**
   * Sets overrides for the service endpoints. In typical usage, the synchronizer will use the commonly defined
   * service endpoints, but for cases where they need to be controlled at the source level, this method can
   * be used. This synchronizer will only use the endpoints applicable to it.
   *
   * @param serviceEndpointsOverride the service endpoints to override the base endpoints
   * @return the builder
   */
  public FDv2PollingSynchronizerBuilder serviceEndpointsOverride(ServiceEndpointsBuilder serviceEndpointsOverride) {
    this.serviceEndpointsOverride = serviceEndpointsOverride.createServiceEndpoints();
    return this;
  }

  /**
   * Sets the Payload Filter that will be used to filter the objects (flags, segments, etc.)
   * from this synchronizer.
   * 
   * @param payloadFilter the filter to be used
   * @return the builder
   */
  public FDv2PollingSynchronizerBuilder payloadFilter(String payloadFilter) {
    this.payloadFilter = payloadFilter;
    return this;
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
