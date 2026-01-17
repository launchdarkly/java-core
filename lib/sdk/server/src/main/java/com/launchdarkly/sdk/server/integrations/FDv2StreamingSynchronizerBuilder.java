package com.launchdarkly.sdk.server.integrations;

import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.internal.events.DiagnosticConfigProperty;
import com.launchdarkly.sdk.server.StandardEndpoints;
import com.launchdarkly.sdk.server.datasources.Synchronizer;
import com.launchdarkly.sdk.server.interfaces.ServiceEndpoints;
import com.launchdarkly.sdk.server.subsystems.ClientContext;
import com.launchdarkly.sdk.server.subsystems.ComponentConfigurer;
import com.launchdarkly.sdk.server.subsystems.DiagnosticDescription;
import com.launchdarkly.sdk.server.subsystems.SynchronizerBuilder;

import java.net.URI;
import java.time.Duration;

/**
 * Contains methods for configuring the streaming synchronizer.
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
 *             // DataSystemComponents.streamingSynchronizer() returns an instance of this builder.
 *             .synchronizers(DataSystemComponents.streamingSynchronizer()
 *                 .initialReconnectDelay(Duration.ofSeconds(5)), DataSystemComponents.pollingSynchronizer())
 *             .fDv1FallbackSynchronizer(DataSystemComponents.fDv1Polling()));
 * </code></pre>
 */
public abstract class FDv2StreamingSynchronizerBuilder implements SynchronizerBuilder, DiagnosticDescription {
  /**
   * The default value for {@link #initialReconnectDelay(Duration)}: 1000 milliseconds.
   */
  public static final Duration DEFAULT_INITIAL_RECONNECT_DELAY = Duration.ofSeconds(1);

  protected Duration initialReconnectDelay = DEFAULT_INITIAL_RECONNECT_DELAY;

  protected ServiceEndpoints serviceEndpointsOverride;

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
  public FDv2StreamingSynchronizerBuilder initialReconnectDelay(Duration initialReconnectDelay) {
    this.initialReconnectDelay = initialReconnectDelay != null ? initialReconnectDelay : DEFAULT_INITIAL_RECONNECT_DELAY;
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
  public FDv2StreamingSynchronizerBuilder serviceEndpointsOverride(ServiceEndpointsBuilder serviceEndpointsOverride) {
    this.serviceEndpointsOverride = serviceEndpointsOverride.createServiceEndpoints();
    return this;
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
