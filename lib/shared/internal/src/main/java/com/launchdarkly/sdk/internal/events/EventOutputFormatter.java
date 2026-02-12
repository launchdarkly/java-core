package com.launchdarkly.sdk.internal.events;

import com.google.gson.stream.JsonWriter;
import com.launchdarkly.sdk.AttributeRef;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.internal.events.EventSummarizer.CounterValue;
import com.launchdarkly.sdk.internal.events.EventSummarizer.FlagInfo;
import com.launchdarkly.sdk.internal.events.EventSummarizer.SimpleIntKeyedMap;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;

import static com.launchdarkly.sdk.internal.GsonHelpers.gsonInstance;

/**
 * Transforms analytics events and summary data into the JSON format that we send to LaunchDarkly.
 * Rather than creating intermediate objects to represent this schema, we use the Gson streaming
 * output API to construct JSON directly.
 * <p>
 * Test coverage for this logic is in EventOutputTest and DefaultEventProcessorOutputTest. The
 * handling of context data and private attribute redaction is implemented in EventContextFormatter
 * and tested in more detail in EventContextFormatterTest.
 */
final class EventOutputFormatter {
  private final EventContextFormatter contextFormatter;

  EventOutputFormatter(EventsConfiguration config) {
    this.contextFormatter = new EventContextFormatter(
        config.allAttributesPrivate,
        config.privateAttributes.toArray(new AttributeRef[config.privateAttributes.size()]));
  }

  int writeOutputEvents(Event[] events, List<EventSummarizer.EventSummary> summaries, Writer writer) throws IOException {
    int count = 0;
    JsonWriter jsonWriter = new JsonWriter(writer);
    jsonWriter.beginArray();
    for (Event event: events) {
      if (writeOutputEvent(event, jsonWriter)) {
        count++;
      }
    }
    for (EventSummarizer.EventSummary summary : summaries) {
      if (!summary.isEmpty()) {
        writeSummaryEvent(summary, jsonWriter);
        count++;
      }
    }
    jsonWriter.endArray();
    jsonWriter.flush();
    return count;
  }

  private boolean writeOutputEvent(Event event, JsonWriter jw) throws IOException {
    if (event.getContext() == null || !event.getContext().isValid()) {
      // The SDK should never send us an event without a valid context, but if we somehow get one,
      // just skip the event since there's no way to serialize it.
      return false;
    }
    if (event instanceof Event.FeatureRequest) {
      Event.FeatureRequest fe = (Event.FeatureRequest)event;
      jw.beginObject();
      writeKindAndCreationDate(jw, fe.isDebug() ? "debug" : "feature", event.getCreationDate());
      jw.name("key").value(fe.getKey());
      writeContext(fe.getContext(), jw, !fe.isDebug());
      if (fe.getVersion() >= 0) {
        jw.name("version");
        jw.value(fe.getVersion());
      }
      if (fe.getVariation() >= 0) {
        jw.name("variation");
        jw.value(fe.getVariation());
      }
      writeLDValue("value", fe.getValue(), jw);
      writeLDValue("default", fe.getDefaultVal(), jw);
      if (fe.getPrereqOf() != null) {
        jw.name("prereqOf");
        jw.value(fe.getPrereqOf());
      }
      writeEvaluationReason(fe.getReason(), jw);
      jw.endObject();
    } else if (event instanceof Event.Identify) {
      jw.beginObject();
      writeKindAndCreationDate(jw, "identify", event.getCreationDate());
      writeContext(event.getContext(), jw, false);
      jw.endObject();
    } else if (event instanceof Event.Custom) {
      Event.Custom ce = (Event.Custom)event;
      jw.beginObject();
      writeKindAndCreationDate(jw, "custom", event.getCreationDate());
      jw.name("key").value(ce.getKey());
      writeContext(ce.getContext(), jw, false);
      writeLDValue("data", ce.getData(), jw);
      if (ce.getMetricValue() != null) {
        jw.name("metricValue");
        jw.value(ce.getMetricValue());
      }
      jw.endObject();
    } else if (event instanceof Event.Index) {
      jw.beginObject();
      writeKindAndCreationDate(jw, "index", event.getCreationDate());
      writeContext(event.getContext(), jw, false);
      jw.endObject();
    } else if (event instanceof Event.MigrationOp) {
      jw.beginObject();
      writeKindAndCreationDate(jw, "migration_op", event.getCreationDate());
      writeContext(event.getContext(), jw, false);

      Event.MigrationOp me = (Event.MigrationOp)event;
      jw.name("operation").value(me.getOperation());

      long samplingRatio = me.getSamplingRatio();
      if(samplingRatio != 1) {
        jw.name("samplingRatio").value(samplingRatio);
      }

      writeMigrationEvaluation(jw, me);
      writeMeasurements(jw, me);

      jw.endObject();
    } else {
      return false;
    }
    return true;
  }

  private static void writeMeasurements(JsonWriter jw, Event.MigrationOp me) throws IOException {
    jw.name("measurements");
    jw.beginArray();

    writeInvokedMeasurement(jw, me);
    writeConsistencyMeasurement(jw, me);
    writeLatencyMeasurement(jw, me);
    writeErrorMeasurement(jw, me);

    jw.endArray(); // end measurements
  }

  private static void writeErrorMeasurement(JsonWriter jw, Event.MigrationOp me) throws IOException {
    Event.MigrationOp.ErrorMeasurement errorMeasurement = me.getErrorMeasurement();
    if(errorMeasurement != null && errorMeasurement.hasMeasurement()) {
      jw.beginObject();
      jw.name("key").value("error");
      jw.name("values");
      jw.beginObject();
      if(errorMeasurement.hasOldError()) {
        jw.name("old").value(errorMeasurement.hasOldError());
      }
      if(errorMeasurement.hasNewError()) {
        jw.name("new").value(errorMeasurement.hasNewError());
      }
      jw.endObject(); // end of values
      jw.endObject(); // end of measurement
    }
  }

  private static void writeLatencyMeasurement(JsonWriter jw, Event.MigrationOp me) throws IOException {
    Event.MigrationOp.LatencyMeasurement latencyMeasurement = me.getLatencyMeasurement();
    if(latencyMeasurement != null && latencyMeasurement.hasMeasurement()) {
      jw.beginObject();

      jw.name("key").value("latency_ms");

      jw.name("values");
      jw.beginObject();
      if(latencyMeasurement.getOldLatencyMs() != null) {
        jw.name("old").value(latencyMeasurement.getOldLatencyMs());
      }
      if(latencyMeasurement.getNewLatencyMs() != null) {
        jw.name("new").value(latencyMeasurement.getNewLatencyMs());
      }

      jw.endObject(); // end of values
      jw.endObject(); // end of measurement
    }
  }

  private static void writeConsistencyMeasurement(JsonWriter jw, Event.MigrationOp me) throws IOException {
    Event.MigrationOp.ConsistencyMeasurement consistencyMeasurement = me.getConsistencyMeasurement();
    if(consistencyMeasurement != null) {
      jw.beginObject();
      jw.name("key").value("consistent");
      jw.name("value").value(consistencyMeasurement.isConsistent());
      if(consistencyMeasurement.getSamplingRatio() != 1) {
        jw.name("samplingRatio").value(consistencyMeasurement.getSamplingRatio());
      }
      jw.endObject(); // end measurement
    }
  }

  private static void writeInvokedMeasurement(JsonWriter jw, Event.MigrationOp me) throws IOException {
    jw.beginObject();
    jw.name("key").value("invoked");
    Event.MigrationOp.InvokedMeasurement invokedMeasurement = me.getInvokedMeasurement();

    jw.name("values");
    jw.beginObject();
    if(invokedMeasurement.wasOldInvoked()) {
      jw.name("old").value(invokedMeasurement.wasOldInvoked());
    }
    if(invokedMeasurement.wasNewInvoked()) {
      jw.name("new").value(invokedMeasurement.wasNewInvoked());
    }
    jw.endObject(); // end values
    jw.endObject(); // end measurement
  }

  private void writeMigrationEvaluation(JsonWriter jw, Event.MigrationOp me) throws IOException {
    jw.name("evaluation");
    jw.beginObject();
    jw.name("key").value(me.getFeatureKey());
    if (me.getVariation() >= 0) {
      jw.name("variation");
      jw.value(me.getVariation());
    }
    if (me.getFlagVersion() >= 0) {
      jw.name("version");
      jw.value(me.getFlagVersion());
    }
    writeLDValue("value", me.getValue(), jw);
    writeLDValue("default", me.getDefaultVal(), jw);
    writeEvaluationReason(me.getReason(), jw);
    jw.endObject();
  }

  private void writeSummaryEvent(EventSummarizer.EventSummary summary, JsonWriter jw) throws IOException {
    jw.beginObject();

    jw.name("kind");
    jw.value("summary");

    jw.name("startDate");
    jw.value(summary.startDate);
    jw.name("endDate");
    jw.value(summary.endDate);

    // Include context if present (per-context summarization)
    if (summary.context != null) {
      writeContext(summary.context, jw, true); // redact anonymous attributes
    }

    jw.name("features");
    jw.beginObject();

    for (Map.Entry<String, FlagInfo> flag: summary.counters.entrySet()) {
      String flagKey = flag.getKey();
      FlagInfo flagInfo = flag.getValue();

      jw.name(flagKey);
      jw.beginObject();

      writeLDValue("default", flagInfo.defaultVal, jw);
      jw.name("contextKinds").beginArray();
      for (String kind: flagInfo.contextKinds) {
        jw.value(kind);
      }
      jw.endArray();

      jw.name("counters");
      jw.beginArray();

      for (int i = 0; i < flagInfo.versionsAndVariations.size(); i++) {
        int version = flagInfo.versionsAndVariations.keyAt(i);
        SimpleIntKeyedMap<CounterValue> variations = flagInfo.versionsAndVariations.valueAt(i);
        for (int j = 0; j < variations.size(); j++) {
          int variation = variations.keyAt(j);
          CounterValue counter = variations.valueAt(j);

          jw.beginObject();

          if (variation >= 0) {
            jw.name("variation").value(variation);
          }
          if (version >= 0) {
            jw.name("version").value(version);
          } else {
            jw.name("unknown").value(true);
          }
          writeLDValue("value", counter.flagValue, jw);
          jw.name("count").value(counter.count);

          jw.endObject();
        }
      }

      jw.endArray(); // end of "counters" array
      jw.endObject(); // end of this flag
    }

    jw.endObject(); // end of "features"
    jw.endObject(); // end of summary event object
  }

  private void writeKindAndCreationDate(JsonWriter jw, String kind, long creationDate) throws IOException {
    jw.name("kind").value(kind);
    jw.name("creationDate").value(creationDate);
  }

  private void writeContext(LDContext context, JsonWriter jw, boolean redactAnonymous) throws IOException {
    jw.name("context");
    contextFormatter.write(context, jw, redactAnonymous);
  }

  private void writeLDValue(String key, LDValue value, JsonWriter jw) throws IOException {
    if (value == null || value.isNull()) {
      return;
    }
    jw.name(key);
    gsonInstance().toJson(value, LDValue.class, jw); // LDValue defines its own custom serializer
  }

  private void writeEvaluationReason(EvaluationReason er, JsonWriter jw) throws IOException {
    if (er == null) {
      return;
    }
    jw.name("reason");
    gsonInstance().toJson(er, EvaluationReason.class, jw); // EvaluationReason defines its own custom serializer
  }
}
