package com.launchdarkly.sdk.internal.events;

import com.launchdarkly.sdk.ArrayBuilder;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.ObjectBuilder;

import java.util.List;

/**
 * Base class for diagnostic events. This class and its subclasses are used only for JSON serialization.
 */
public class DiagnosticEvent {
  final boolean initEvent;
  final LDValue value;
  
  private DiagnosticEvent(boolean initEvent, LDValue value) {
    this.initEvent = initEvent;
    this.value = value;
  }
  
  /**
   * Returns the JSON representation of the event.
   * @return the JSON representation as an {@link LDValue}
   */
  public LDValue getJsonValue() {
    return value;
  }
  
  static DiagnosticEvent makeInit(
      long creationDate,
      DiagnosticId diagnosticId,
      LDValue sdk,
      LDValue configuration,
      LDValue platform
      ) {
    return new DiagnosticEvent(
        true,
        baseBuilder("diagnostic-init", creationDate, diagnosticId)
          .put("sdk", sdk)
          .put("configuration", configuration)
          .put("platform", platform)
          .build()
        );
  }
  
  static DiagnosticEvent makeStatistics(
      long creationDate,
      DiagnosticId diagnosticId,
      long dataSinceDate,
      long droppedEvents,
      long deduplicatedUsers,
      long eventsInLastBatch,
      List<StreamInit> streamInits
      ) {
    ObjectBuilder b = baseBuilder("diagnostic", creationDate, diagnosticId)
        .put("dataSinceDate", dataSinceDate)
        .put("droppedEvents", droppedEvents)
        .put("deduplicatedUsers", deduplicatedUsers)
        .put("eventsInLastBatch", eventsInLastBatch);
    ArrayBuilder ab = LDValue.buildArray();
    if (streamInits != null) {
      for (StreamInit si: streamInits) {
        ab.add(LDValue.buildObject()
            .put("timestamp", si.timestamp)
            .put("durationMillis", si.durationMillis)
            .put("failed", si.failed)
            .build());
      }
    }
    b.put("streamInits", ab.build());
    return new DiagnosticEvent(false, b.build());
  }
  
  private static ObjectBuilder baseBuilder(String kind, long creationDate, DiagnosticId id) {
    return LDValue.buildObject()
        .put("kind", kind)
        .put("creationDate", creationDate)
        .put("id", LDValue.buildObject()
          .put("diagnosticId", id.diagnosticId)
          .put("sdkKeySuffix",  id.sdkKeySuffix)
          .build()
        );
  }
  
  static class StreamInit {
    final long timestamp;
    final long durationMillis;
    final boolean failed;

    StreamInit(long timestamp, long durationMillis, boolean failed) {
      this.timestamp = timestamp;
      this.durationMillis = durationMillis;
      this.failed = failed;
    }
  }  
}
