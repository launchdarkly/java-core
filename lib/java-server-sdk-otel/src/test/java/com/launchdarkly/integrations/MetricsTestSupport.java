package com.launchdarkly.integrations;

import com.launchdarkly.sdk.server.interfaces.DataSourceDescriptor;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.DoublePointData;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.function.Supplier;

import static com.launchdarkly.integrations.MetricsHook.ATTR_DATA_SOURCE_NAME;
import static com.launchdarkly.integrations.MetricsHook.ATTR_DATA_SOURCE_PROTOCOL;
import static com.launchdarkly.integrations.MetricsHook.ATTR_DATA_SOURCE_STATE;
import static com.launchdarkly.integrations.MetricsHook.ATTR_DATA_SOURCE_TRANSPORT;
import static com.launchdarkly.integrations.MetricsHook.NOT_PROVIDED_VALUE;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/**
 * Shared helpers for the metrics hook tests: an in-memory meter provider and metric lookups.
 */
final class MetricsTestSupport {
  static final DataSourceDescriptor STREAMING = DataSourceDescriptor.of(
      DataSourceDescriptor.Protocol.FDV2, DataSourceDescriptor.Transport.STREAMING, "streaming");
  static final DataSourceDescriptor POLLING = DataSourceDescriptor.of(
      DataSourceDescriptor.Protocol.FDV2, DataSourceDescriptor.Transport.POLLING, "polling");
  static final DataSourceDescriptor RELAY_POLL = DataSourceDescriptor.of(
      DataSourceDescriptor.Protocol.FDV2, DataSourceDescriptor.Transport.POLLING, "relay-poll");
  static final DataSourceDescriptor FDV1_STREAMING = DataSourceDescriptor.of(
      DataSourceDescriptor.Protocol.FDV1, DataSourceDescriptor.Transport.STREAMING, "streaming");

  private MetricsTestSupport() {
  }

  static final class Setup {
    final InMemoryMetricReader reader = InMemoryMetricReader.create();
    final OpenTelemetry openTelemetry;
    final MetricsHook hook;

    Setup() {
      this(Attributes.empty());
    }

    Setup(Attributes constantAttributes) {
      SdkMeterProvider provider = SdkMeterProvider.builder().registerMetricReader(reader).build();
      openTelemetry = OpenTelemetrySdk.builder().setMeterProvider(provider).build();
      hook = MetricsHook.builder().openTelemetry(openTelemetry).attributes(constantAttributes).build();
    }

    Collection<MetricData> collect() {
      return reader.collectAllMetrics();
    }

    // Collects until the condition holds, or fails after the timeout.
    Collection<MetricData> awaitMetrics(Duration timeout, java.util.function.Predicate<Collection<MetricData>> condition) {
      long deadline = System.currentTimeMillis() + timeout.toMillis();
      while (true) {
        Collection<MetricData> metrics = collect();
        if (condition.test(metrics)) {
          return metrics;
        }
        if (System.currentTimeMillis() > deadline) {
          fail("condition not met within " + timeout);
        }
        try {
          Thread.sleep(20);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          fail("interrupted");
        }
      }
    }
  }

  static MetricData metric(Collection<MetricData> metrics, String name) {
    for (MetricData m : metrics) {
      if (m.getName().equals(name)) {
        return m;
      }
    }
    return null;
  }

  static MetricData requireMetric(Collection<MetricData> metrics, String name) {
    MetricData m = metric(metrics, name);
    assertNotNull("no metric named " + name, m);
    return m;
  }

  static boolean hasMetric(Collection<MetricData> metrics, String name) {
    return metric(metrics, name) != null;
  }

  static boolean hasAttributes(Attributes actual, Attributes want) {
    return actual.asMap().entrySet().containsAll(want.asMap().entrySet());
  }

  static long sumLong(MetricData m, Attributes want) {
    long total = 0;
    for (LongPointData p : m.getLongSumData().getPoints()) {
      if (hasAttributes(p.getAttributes(), want)) {
        total += p.getValue();
      }
    }
    return total;
  }

  static double sumDouble(MetricData m, Attributes want) {
    double total = 0;
    for (DoublePointData p : m.getDoubleSumData().getPoints()) {
      if (hasAttributes(p.getAttributes(), want)) {
        total += p.getValue();
      }
    }
    return total;
  }

  static Long gaugeLong(MetricData m, Attributes want) {
    for (LongPointData p : m.getLongGaugeData().getPoints()) {
      if (hasAttributes(p.getAttributes(), want)) {
        return p.getValue();
      }
    }
    return null;
  }

  static Double gaugeDouble(MetricData m, Attributes want) {
    for (DoublePointData p : m.getDoubleGaugeData().getPoints()) {
      if (hasAttributes(p.getAttributes(), want)) {
        return p.getValue();
      }
    }
    return null;
  }

  static long histogramCount(MetricData m, Attributes want) {
    long total = 0;
    for (HistogramPointData p : m.getHistogramData().getPoints()) {
      if (hasAttributes(p.getAttributes(), want)) {
        total += p.getCount();
      }
    }
    return total;
  }

  static long requireGaugeLong(Collection<MetricData> metrics, String name, Attributes want) {
    Long value = gaugeLong(requireMetric(metrics, name), want);
    assertNotNull("no " + name + " series with attributes " + want, value);
    return value;
  }

  static AttributesBuilder source(DataSourceDescriptor d) {
    return Attributes.builder()
        .put(ATTR_DATA_SOURCE_NAME, d.getName() == null ? NOT_PROVIDED_VALUE : d.getName())
        .put(ATTR_DATA_SOURCE_PROTOCOL, d.getProtocol() == null ? NOT_PROVIDED_VALUE : d.getProtocol().getValue())
        .put(ATTR_DATA_SOURCE_TRANSPORT, d.getTransport() == null ? NOT_PROVIDED_VALUE : d.getTransport().getValue());
  }

  static Attributes sourceAttrs(DataSourceDescriptor d) {
    return source(d).build();
  }

  static Attributes withState(DataSourceDescriptor d, DataSourceStatusProvider.State state) {
    return source(d).put(ATTR_DATA_SOURCE_STATE, state.name()).build();
  }

  static DataSourceStatusProvider.Status statusAt(DataSourceStatusProvider.State state, Instant since) {
    return new DataSourceStatusProvider.Status(state, since, null);
  }

  static DataSourceStatusProvider.Status statusAt(DataSourceStatusProvider.State state, Instant since,
      DataSourceStatusProvider.ErrorInfo error) {
    return new DataSourceStatusProvider.Status(state, since, error);
  }

  static <T> T await(Duration timeout, Supplier<T> fn) {
    long deadline = System.currentTimeMillis() + timeout.toMillis();
    while (true) {
      T value = fn.get();
      if (value != null) {
        return value;
      }
      if (System.currentTimeMillis() > deadline) {
        fail("value not available within " + timeout);
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("interrupted");
      }
    }
  }
}
