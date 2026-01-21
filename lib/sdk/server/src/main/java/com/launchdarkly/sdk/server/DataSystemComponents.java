package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.server.datasources.Initializer;
import com.launchdarkly.sdk.server.datasources.Synchronizer;
import com.launchdarkly.sdk.server.integrations.FDv2PollingInitializerBuilder;
import com.launchdarkly.sdk.server.integrations.FDv2PollingSynchronizerBuilder;
import com.launchdarkly.sdk.server.integrations.FDv2StreamingSynchronizerBuilder;
import com.launchdarkly.sdk.server.integrations.PollingDataSourceBuilder;
import com.launchdarkly.sdk.server.interfaces.ServiceEndpoints;
import com.launchdarkly.sdk.server.subsystems.DataSourceBuilderContext;

import java.net.URI;

import static com.launchdarkly.sdk.server.ComponentsImpl.toHttpProperties;

/**
 * Components for use with the data system.
 * <p>
 * This class is not stable, and not subject to any backwards compatibility guarantees or semantic versioning.
 * It is in early access. If you want access to this feature please join the EAP. https://launchdarkly.com/docs/sdk/features/data-saving-mode
 * </p>
 */
public final class DataSystemComponents {

  static class FDv2PollingInitializerBuilderImpl extends FDv2PollingInitializerBuilder {
    @Override
    public Initializer build(DataSourceBuilderContext context) {
      ServiceEndpoints endpoints = serviceEndpointsOverride != null
              ? serviceEndpointsOverride
              : context.getServiceEndpoints();
      URI configuredBaseUri = StandardEndpoints.selectBaseUri(
              endpoints.getPollingBaseUri(),
              StandardEndpoints.DEFAULT_POLLING_BASE_URI,
              "Polling",
              context.getBaseLogger());

      DefaultFDv2Requestor requestor = new DefaultFDv2Requestor(
              toHttpProperties(context.getHttp()),
              configuredBaseUri,
              StandardEndpoints.FDV2_POLLING_REQUEST_PATH,
              payloadFilter,
              context.getBaseLogger());

      return new PollingInitializerImpl(
              requestor,
              context.getBaseLogger(),
              context.getSelectorSource()
      );
    }
  }

  static class FDv2PollingSynchronizerBuilderImpl extends FDv2PollingSynchronizerBuilder {
    @Override
    public Synchronizer build(DataSourceBuilderContext context) {
      ServiceEndpoints endpoints = serviceEndpointsOverride != null
              ? serviceEndpointsOverride
              : context.getServiceEndpoints();
      URI configuredBaseUri = StandardEndpoints.selectBaseUri(
              endpoints.getPollingBaseUri(),
              StandardEndpoints.DEFAULT_POLLING_BASE_URI,
              "Polling",
              context.getBaseLogger());

      DefaultFDv2Requestor requestor = new DefaultFDv2Requestor(
              toHttpProperties(context.getHttp()),
              configuredBaseUri,
              StandardEndpoints.FDV2_POLLING_REQUEST_PATH,
              payloadFilter,
              context.getBaseLogger());

      return new PollingSynchronizerImpl(
              requestor,
              context.getBaseLogger(),
              context.getSelectorSource(),
              context.getSharedExecutor(),
              pollInterval
      );
    }
  }

  static class FDv2StreamingSynchronizerBuilderImpl extends FDv2StreamingSynchronizerBuilder {
    @Override
    public Synchronizer build(DataSourceBuilderContext context) {
      ServiceEndpoints endpoints = serviceEndpointsOverride != null
              ? serviceEndpointsOverride
              : context.getServiceEndpoints();
      URI configuredBaseUri = StandardEndpoints.selectBaseUri(
              endpoints.getStreamingBaseUri(),
              StandardEndpoints.DEFAULT_STREAMING_BASE_URI,
              "Streaming",
              context.getBaseLogger());

      return new StreamingSynchronizerImpl(
              toHttpProperties(context.getHttp()),
              configuredBaseUri,
              StandardEndpoints.FDV2_STREAMING_REQUEST_PATH,
              context.getBaseLogger(),
              context.getSelectorSource(),
              payloadFilter,
              initialReconnectDelay
      );
    }
  }

  private DataSystemComponents() {}

  /**
   * Get a builder for a polling initializer.
   *
   * @return the polling initializer builder
   */
  public static FDv2PollingInitializerBuilder pollingInitializer() {
    return new FDv2PollingInitializerBuilderImpl();
  }

  /**
   * Get a builder for a polling synchronizer.
   *
   * @return the polling synchronizer builder
   */
  public static FDv2PollingSynchronizerBuilder pollingSynchronizer() {
    return new FDv2PollingSynchronizerBuilderImpl();
  }

  /**
   * Get a builder for a streaming synchronizer.
   *
   * @return the streaming synchronizer builder
   */
  public static FDv2StreamingSynchronizerBuilder streamingSynchronizer() {
    return new FDv2StreamingSynchronizerBuilderImpl();
  }

  /**
   * Get a builder for a FDv1 compatible polling data source.
   * <p>
   * This is intended for use as a fallback.
   * </p>
   *
   * @return the FDv1 compatible polling data source builder
   */
  public static PollingDataSourceBuilder fDv1Polling() {
    return Components.pollingDataSource();
  }
}

