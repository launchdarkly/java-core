package com.launchdarkly.integrations;

import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.integrations.EvaluationSeriesContext;
import com.launchdarkly.sdk.server.integrations.Hook;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;

import java.util.HashMap;
import java.util.Map;

public class TracingHook extends Hook {

  static final String PROVIDER_NAME = "LaunchDarkly";
  static final String HOOK_NAME = "LaunchDarkly Tracing Hook";
  static final String INSTRUMENTATION_NAME = "launchdarkly-client";
  static final String DATA_KEY_SPAN = "variationSpan";
  static final String EVENT_NAME = "feature_flag";
  static final String SEMCONV_FEATURE_FLAG_PROVIDER_NAME = "feature_flag.provider.name";
  static final String SEMCONV_FEATURE_FLAG_KEY = "feature_flag.key";
  static final String SEMCONV_FEATURE_FLAG_VALUE = "feature_flag.result.value";
  static final String SEMCONV_FEATURE_FLAG_CONTEXT_ID = "feature_flag.context.id";
  static final String SEMCONV_FEATURE_FLAG_VARIATION_INDEX = "feature_flag.result.variationIndex";
  static final String SEMCONV_FEATURE_FLAG_IN_EXPERIMENT = "feature_flag.result.reason.inExperiment";

  private final boolean withSpans;
  private final boolean withValue;

  /**
   * Creates a {@link TracingHook}
   *
   * @param withSpans will include child spans for the various hook series when they happen
   * @param withValue will include the value of the feature flag in the recorded evaluation events
   */
  TracingHook(boolean withSpans, boolean withValue) {
    super(HOOK_NAME);
    this.withSpans = withSpans;
    this.withValue = withValue;
  }

  @Override
  public Map<String, Object> beforeEvaluation(EvaluationSeriesContext seriesContext, Map<String, Object> seriesData) {
    return beforeEvaluationInternal(GlobalOpenTelemetry.get().getTracer(INSTRUMENTATION_NAME), seriesContext, seriesData);
  }

  // visible for testing
  Map<String, Object> beforeEvaluationInternal(Tracer tracer, EvaluationSeriesContext seriesContext, Map<String, Object> seriesData) {
    if (!withSpans) {
      return seriesData;
    }

    SpanBuilder builder = tracer.spanBuilder(seriesContext.method)
        .setParent(Context.current().with(Span.current()));

    AttributesBuilder attrBuilder = Attributes.builder();
    attrBuilder.put(SEMCONV_FEATURE_FLAG_KEY, seriesContext.flagKey);
    attrBuilder.put(SEMCONV_FEATURE_FLAG_PROVIDER_NAME, PROVIDER_NAME);
    attrBuilder.put(SEMCONV_FEATURE_FLAG_CONTEXT_ID, seriesContext.context.getFullyQualifiedKey());
    builder.setAllAttributes(attrBuilder.build());
    Span span = builder.startSpan();
    Map<String, Object> retSeriesData = new HashMap<>(seriesData);
    retSeriesData.put(DATA_KEY_SPAN, span);
    return retSeriesData;
  }

  @Override
  public Map<String, Object> afterEvaluation(EvaluationSeriesContext seriesContext, Map<String, Object> seriesData, EvaluationDetail<LDValue> evaluationDetail) {
    Object value = seriesData.get(DATA_KEY_SPAN);
    if (value instanceof Span) {
      Span span = (Span) value;
      span.end();
    }

    AttributesBuilder attrBuilder = Attributes.builder();
    attrBuilder.put(SEMCONV_FEATURE_FLAG_KEY, seriesContext.flagKey);
    attrBuilder.put(SEMCONV_FEATURE_FLAG_PROVIDER_NAME, PROVIDER_NAME);
    attrBuilder.put(SEMCONV_FEATURE_FLAG_CONTEXT_ID, seriesContext.context.getFullyQualifiedKey());
    if (withValue) {
      attrBuilder.put(SEMCONV_FEATURE_FLAG_VALUE, evaluationDetail.getValue().toJsonString());
    }
    
    if (evaluationDetail.getReason().isInExperiment()) {
      attrBuilder.put(SEMCONV_FEATURE_FLAG_IN_EXPERIMENT, true);
    }
    
    if (evaluationDetail.getVariationIndex() != EvaluationDetail.NO_VARIATION) {
      attrBuilder.put(SEMCONV_FEATURE_FLAG_VARIATION_INDEX, evaluationDetail.getVariationIndex());
    }

    // Here we make best effort the log the event and let the library handle the "no current span" case; which at the
    // time of writing this, it does handle.
    Span.current().addEvent(EVENT_NAME, attrBuilder.build());
    return seriesData;
  }

  /**
   * Builder for creating an {@link TracingHook}.
   */
  public static class Builder {
    private boolean withSpans = false;
    private boolean withValue = false;

    /**
     * The {@link TracingHook} will include child spans for the various hook series when they happen
     * @return the builder
     */
    public Builder withSpans() {
      this.withSpans = true;
      return this;
    }

    /**
     * The {@link TracingHook} will include the value of the feature flag in the recorded evaluation events
     * @return the builder
     */
    public Builder withValue() {
      this.withValue = true;
      return this;
    }

    /**
     * The {@link TracingHook} will include the variant of the feature flag in the recorded evaluation events
     * @return the builder
     * @deprecated Use {@link #withValue()} instead
     */
    @Deprecated
    public Builder withVariant() {
      this.withValue = true;
      return this;
    }

    /**
     * @return the {@link TracingHook}
     */
    public TracingHook build() {
      return new TracingHook(withSpans, withValue);
    }
  }
}
