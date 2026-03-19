package com.launchdarkly.integrations;

import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.integrations.EvaluationSeriesContext;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.launchdarkly.integrations.TracingHook.SEMCONV_FEATURE_FLAG_CONTEXT_ID;
import static com.launchdarkly.integrations.TracingHook.PROVIDER_NAME;
import static com.launchdarkly.integrations.TracingHook.SEMCONV_FEATURE_FLAG_KEY;
import static com.launchdarkly.integrations.TracingHook.SEMCONV_FEATURE_FLAG_PROVIDER_NAME;
import static com.launchdarkly.integrations.TracingHook.SEMCONV_FEATURE_FLAG_VALUE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class TracingHookTest {

  @Test
  public void testCanUseBuilder() {
    TracingHook hook = new TracingHook.Builder().withSpans().withVariant().build();
  }

  @Test
  public void testAddsEventToParentSpanWtihoutVariation() {
    InMemorySpanExporter testExporter = InMemorySpanExporter.create();
    Tracer testTracer = makeTestTracer(testExporter);

    Span rootSpan = testTracer.spanBuilder("rootSpan").startSpan();
    rootSpan.makeCurrent();

    EvaluationSeriesContext testSeriesContext = new EvaluationSeriesContext("LDClient.testMethod", "testKey",
        LDContext.create("testContextKey"), LDValue.of("defaultValue"));

    TracingHook hookUnderTest = new TracingHook(false, false);
    Map<String, Object> seriesData = hookUnderTest.beforeEvaluationInternal(testTracer, testSeriesContext, Collections.emptyMap());
    hookUnderTest.afterEvaluation(testSeriesContext, seriesData, EvaluationDetail.fromValue(LDValue.of("evaluationValue"), 0, EvaluationReason.fallthrough()));
    rootSpan.end();

    List<SpanData> spanDataList = testExporter.getFinishedSpanItems();
    assertEquals(1, spanDataList.size());

    SpanData spanData = spanDataList.get(0);
    assertEquals("rootSpan", spanData.getName());
    assertEquals(1, spanData.getEvents().size());

    Attributes attributes = spanData.getEvents().get(0).getAttributes();
    assertEquals(4, attributes.size());
    assertEquals(PROVIDER_NAME, attributes.get(AttributeKey.stringKey(SEMCONV_FEATURE_FLAG_PROVIDER_NAME)));
    assertEquals("testKey", attributes.get(AttributeKey.stringKey(SEMCONV_FEATURE_FLAG_KEY)));
    assertNull(attributes.get(AttributeKey.stringKey(SEMCONV_FEATURE_FLAG_VALUE)));
    assertEquals("testContextKey", attributes.get(AttributeKey.stringKey(SEMCONV_FEATURE_FLAG_CONTEXT_ID)));
  }

  @Test
  public void testAddsEventToParentSpanWtihVariation() {
    InMemorySpanExporter testExporter = InMemorySpanExporter.create();
    Tracer testTracer = makeTestTracer(testExporter);

    Span rootSpan = testTracer.spanBuilder("rootSpan").startSpan();
    rootSpan.makeCurrent();

    EvaluationSeriesContext testSeriesContext = new EvaluationSeriesContext("LDClient.testMethod", "testKey",
        LDContext.create("testContextKey"), LDValue.of("defaultValue"));

    TracingHook hookUnderTest = new TracingHook(false, true);
    Map<String, Object> seriesData = hookUnderTest.beforeEvaluationInternal(testTracer, testSeriesContext, Collections.emptyMap());
    hookUnderTest.afterEvaluation(testSeriesContext, seriesData, EvaluationDetail.fromValue(LDValue.buildObject().put("evalKey", "evalValue").build(), 0, EvaluationReason.fallthrough()));
    rootSpan.end();

    List<SpanData> spanDataList = testExporter.getFinishedSpanItems();
    assertEquals(1, spanDataList.size());

    SpanData spanData = spanDataList.get(0);
    assertEquals("rootSpan", spanData.getName());
    assertEquals(1, spanData.getEvents().size());

    Attributes attributes = spanData.getEvents().get(0).getAttributes();
    assertEquals(5, attributes.size());
    assertEquals(PROVIDER_NAME, attributes.get(AttributeKey.stringKey(SEMCONV_FEATURE_FLAG_PROVIDER_NAME)));
    assertEquals("testKey", attributes.get(AttributeKey.stringKey(SEMCONV_FEATURE_FLAG_KEY)));
    assertEquals("{\"evalKey\":\"evalValue\"}", attributes.get(AttributeKey.stringKey(SEMCONV_FEATURE_FLAG_VALUE)));
    assertEquals("testContextKey", attributes.get(AttributeKey.stringKey(SEMCONV_FEATURE_FLAG_CONTEXT_ID)));
  }

  @Test
  public void testCreatesChildSpanEventStillOnParent() {
    InMemorySpanExporter testExporter = InMemorySpanExporter.create();
    Tracer testTracer = makeTestTracer(testExporter);

    Span rootSpan = testTracer.spanBuilder("rootSpan").startSpan();
    rootSpan.makeCurrent();

    EvaluationSeriesContext testSeriesContext = new EvaluationSeriesContext("LDClient.testMethod", "testKey",
        LDContext.create("testContextKey"), LDValue.of("defaultValue"));

    TracingHook hookUnderTest = new TracingHook(true, false);
    Map<String, Object> seriesData = hookUnderTest.beforeEvaluationInternal(testTracer, testSeriesContext, Collections.emptyMap());
    hookUnderTest.afterEvaluation(testSeriesContext, seriesData, EvaluationDetail.fromValue(LDValue.of("evaluationValue"), 0, EvaluationReason.fallthrough()));
    rootSpan.end();

    List<SpanData> spanDataList = testExporter.getFinishedSpanItems();
    assertEquals(2, spanDataList.size());
    assertEquals("LDClient.testMethod", spanDataList.get(0).getName());
    assertEquals("rootSpan", spanDataList.get(1).getName());
    Attributes attributes = spanDataList.get(1).getEvents().get(0).getAttributes();
    assertEquals(4, attributes.size());
    assertEquals("testKey", attributes.get(AttributeKey.stringKey(SEMCONV_FEATURE_FLAG_KEY)));
    assertEquals(PROVIDER_NAME, attributes.get(AttributeKey.stringKey(SEMCONV_FEATURE_FLAG_PROVIDER_NAME)));
    assertEquals("testContextKey", attributes.get(AttributeKey.stringKey(SEMCONV_FEATURE_FLAG_CONTEXT_ID)));
  }

  @Test
  public void testCreatesSpanIfThereIsNoParentSpan() {
    InMemorySpanExporter testExporter = InMemorySpanExporter.create();
    Tracer testTracer = makeTestTracer(testExporter);

    EvaluationSeriesContext testSeriesContext = new EvaluationSeriesContext("LDClient.testMethod", "testKey",
        LDContext.create("testContextKey"), LDValue.of("defaultValue"));

    TracingHook hookUnderTest = new TracingHook(true, false);
    Map<String, Object> seriesData = hookUnderTest.beforeEvaluationInternal(testTracer, testSeriesContext, Collections.emptyMap());
    hookUnderTest.afterEvaluation(testSeriesContext, seriesData, EvaluationDetail.fromValue(LDValue.of("evaluationValue"), 0, EvaluationReason.fallthrough()));

    List<SpanData> spanDataList = testExporter.getFinishedSpanItems();
    assertEquals(1, spanDataList.size());

    SpanData spanData = spanDataList.get(0);
    assertEquals("LDClient.testMethod", spanData.getName());

    // event should not be put on created span even when there is no parent
    assertEquals(0, spanData.getEvents().size());
  }

  private Tracer makeTestTracer(SpanExporter exporter) {
    return SdkTracerProvider
        .builder()
        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
        .build().tracerBuilder("test-scope").build();
  }
}
