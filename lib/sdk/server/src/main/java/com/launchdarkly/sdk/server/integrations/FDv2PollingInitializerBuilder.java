package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.internal.events.DiagnosticConfigProperty;
import com.launchdarkly.sdk.server.StandardEndpoints;
import com.launchdarkly.sdk.server.datasources.Initializer;
import com.launchdarkly.sdk.server.interfaces.ServiceEndpoints;
import com.launchdarkly.sdk.server.subsystems.ClientContext;
import com.launchdarkly.sdk.server.subsystems.DataSourceBuilder;
import com.launchdarkly.sdk.server.subsystems.DiagnosticDescription;


/**
 * Contains methods for configuring the polling initializer.
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
 *             // DataSystemComponents.pollingInitializer() returns an instance of this builder.
 *             .initializers(DataSystemComponents.pollingInitializer()
 *                 .pollInterval(Duration.ofMinutes(10)))
 *             .synchronizers(DataSystemComponents.streamingSynchronizer(), DataSystemComponents.pollingSynchronizer())
 *             .fDv1FallbackSynchronizer(DataSystemComponents.fDv1Polling()));
 * </code></pre>
 */
public abstract class FDv2PollingInitializerBuilder implements DataSourceBuilder<Initializer>, DiagnosticDescription {
  protected ServiceEndpoints serviceEndpointsOverride;

  protected String payloadFilter;

  /**
   * Sets overrides for the service endpoints. In typical usage, the initializer will use the commonly defined
   * service endpoints, but for cases where they need to be controlled at the source level, this method can
   * be used. This initializer will only use the endpoints applicable to it.
   *
   * @param serviceEndpointsOverride the service endpoints to override the base endpoints
   * @return the builder
   */
  public FDv2PollingInitializerBuilder serviceEndpointsOverride(ServiceEndpointsBuilder serviceEndpointsOverride) {
    this.serviceEndpointsOverride = serviceEndpointsOverride.createServiceEndpoints();
    return this;
  }

  /**
   * Sets the Payload Filter that will be used to filter the objects (flags, segments, etc.)
   * from this initializer.
   * 
   * @param payloadFilter the filter to be used
   * @return the builder
   */
  public FDv2PollingInitializerBuilder payloadFilter(String payloadFilter) {
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
            .put(DiagnosticConfigProperty.USING_RELAY_DAEMON.name, false)
            .build();
  }
}
