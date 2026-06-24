package com.launchdarkly.sdk.server.ai;

import com.launchdarkly.logging.LDLogAdapter;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LDSLF4J;
import com.launchdarkly.logging.Logs;
import com.launchdarkly.sdk.ArrayBuilder;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.ObjectBuilder;
import com.launchdarkly.sdk.server.ai.datamodel.LDAITrackingTypes.TokenUsage;
import com.launchdarkly.sdk.server.ai.internal.ResumptionTokens;
import com.launchdarkly.sdk.server.interfaces.LDClientInterface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reports graph-level events for a single invocation of an {@link AgentGraphDefinition}.
 * <p>
 * An {@code AIGraphTracker} is obtained from an enabled graph definition via
 * {@link AgentGraphDefinition#createTracker()}, or reconstructed from a resumption token via
 * {@link LDAIClient#createGraphTracker(String, LDContext)}.
 * <p>
 * Graph-level methods (invocation, duration, tokens, path) are at-most-once: a second call on
 * the same tracker is silently dropped. Edge-level methods (redirect, handoff) are multi-fire —
 * each call records a distinct event.
 * <p>
 * Implementations are thread-safe.
 */
public final class AIGraphTracker {

  private static final String GRAPH_INVOCATION_SUCCESS = "$ld:ai:graph:invocation_success";
  private static final String GRAPH_INVOCATION_FAILURE = "$ld:ai:graph:invocation_failure";
  private static final String GRAPH_DURATION_TOTAL = "$ld:ai:graph:duration:total";
  private static final String GRAPH_TOTAL_TOKENS = "$ld:ai:graph:total_tokens";
  private static final String GRAPH_PATH = "$ld:ai:graph:path";
  private static final String GRAPH_REDIRECT = "$ld:ai:graph:redirect";
  private static final String GRAPH_HANDOFF_SUCCESS = "$ld:ai:graph:handoff_success";
  private static final String GRAPH_HANDOFF_FAILURE = "$ld:ai:graph:handoff_failure";

  private final LDClientInterface client;
  private final LDContext context;
  private final LDLogger logger;

  private final String runId;
  private final String graphKey;
  private final String variationKey;
  private final int version;

  private final String resumptionToken;

  // At-most-once guards: null = not yet recorded, non-null = recorded.
  // trackInvocationSuccess and trackInvocationFailure share invocationRecorded:
  //   true = success was recorded, false = failure was recorded.
  private final AtomicReference<Boolean> invocationRecorded = new AtomicReference<>();
  private final AtomicReference<Double> durationRecorded = new AtomicReference<>();
  private final AtomicReference<TokenUsage> tokensRecorded = new AtomicReference<>();
  private final AtomicReference<List<String>> pathRecorded = new AtomicReference<>();

  AIGraphTracker(
      LDClientInterface client,
      String runId,
      String graphKey,
      String variationKey,
      int version,
      LDContext context,
      LDLogger logger) {
    this.client = Objects.requireNonNull(client, "client");
    this.runId = Objects.requireNonNull(runId, "runId");
    this.graphKey = Objects.requireNonNull(graphKey, "graphKey");
    this.variationKey = variationKey;
    this.version = version;
    this.context = Objects.requireNonNull(context, "context");
    this.logger = Objects.requireNonNull(logger, "logger");

    this.resumptionToken = ResumptionTokens.encodeGraph(runId, graphKey, variationKey, version);
  }

  /**
   * Reconstructs a graph tracker from a resumption token, preserving the original run identity.
   *
   * @param token the resumption token produced by {@link #getResumptionToken()}
   * @param client the LaunchDarkly client; must not be {@code null}
   * @param context the evaluation context; must not be {@code null}
   * @return a new tracker with the decoded run identity
   * @throws IllegalArgumentException if the token is malformed
   */
  public static AIGraphTracker fromResumptionToken(
      String token, LDClientInterface client, LDContext context) {
    ResumptionTokens.DecodedGraph d = ResumptionTokens.decodeGraph(token);
    int version = Math.max(1, d.getVersion());
    return new AIGraphTracker(
        client,
        d.getRunId(),
        d.getGraphKey(),
        d.getVariationKey(),
        version,
        context,
        defaultLogger());
  }

  /**
   * Records that the graph invocation succeeded.
   * <p>
   * At-most-once and mutually exclusive with {@link #trackInvocationFailure()}: whichever is
   * called first wins.
   */
  public void trackInvocationSuccess() {
    if (!invocationRecorded.compareAndSet(null, Boolean.TRUE)) {
      logger.warn("Skipping trackInvocationSuccess: invocation already recorded on this graph tracker.");
      return;
    }
    client.trackMetric(GRAPH_INVOCATION_SUCCESS, context, baseData().build(), 1);
  }

  /**
   * Records that the graph invocation failed.
   * <p>
   * At-most-once and mutually exclusive with {@link #trackInvocationSuccess()}: whichever is
   * called first wins.
   */
  public void trackInvocationFailure() {
    if (!invocationRecorded.compareAndSet(null, Boolean.FALSE)) {
      logger.warn("Skipping trackInvocationFailure: invocation already recorded on this graph tracker.");
      return;
    }
    client.trackMetric(GRAPH_INVOCATION_FAILURE, context, baseData().build(), 1);
  }

  /**
   * Records the total wall-clock duration of the graph invocation.
   * <p>
   * At-most-once: subsequent calls on the same tracker are silently dropped.
   *
   * @param durationMs the duration in milliseconds
   */
  public void trackDuration(double durationMs) {
    if (!durationRecorded.compareAndSet(null, durationMs)) {
      logger.warn("Skipping trackDuration: duration already recorded on this graph tracker.");
      return;
    }
    client.trackMetric(GRAPH_DURATION_TOTAL, context, baseData().build(), durationMs);
  }

  /**
   * Records the total token usage for the graph invocation.
   * <p>
   * At-most-once: subsequent calls are silently dropped. Calls where all counts are zero do not
   * consume the at-most-once slot.
   *
   * @param tokens the token usage; ignored if {@code null}
   */
  public void trackTotalTokens(TokenUsage tokens) {
    if (tokens == null) {
      logger.debug("Skipping trackTotalTokens: tokens was null.");
      return;
    }
    boolean hasPositive = tokens.getTotal() > 0 || tokens.getInput() > 0 || tokens.getOutput() > 0;
    if (!hasPositive) {
      return;
    }
    if (!tokensRecorded.compareAndSet(null, tokens)) {
      logger.warn("Skipping trackTotalTokens: token usage already recorded on this graph tracker.");
      return;
    }
    if (tokens.getTotal() > 0) {
      client.trackMetric(GRAPH_TOTAL_TOKENS, context, baseData().build(), tokens.getTotal());
    }
  }

  /**
   * Records the ordered path of node keys visited during the graph invocation.
   * <p>
   * At-most-once: subsequent calls on the same tracker are silently dropped.
   *
   * @param path the ordered list of node keys; ignored if {@code null} or empty
   */
  public void trackPath(List<String> path) {
    if (path == null || path.isEmpty()) {
      logger.debug("Skipping trackPath: path was null or empty.");
      return;
    }
    List<String> snapshot = Collections.unmodifiableList(new ArrayList<>(path));
    if (!pathRecorded.compareAndSet(null, snapshot)) {
      logger.warn("Skipping trackPath: path already recorded on this graph tracker.");
      return;
    }
    ArrayBuilder ab = LDValue.buildArray();
    for (String s : path) {
      ab.add(LDValue.of(s));
    }
    LDValue data = baseData().put("path", ab.build()).build();
    client.trackMetric(GRAPH_PATH, context, data, 1);
  }

  /**
   * Records a redirect event, where the graph transitioned from one node to a different target
   * than the edge originally specified.
   * <p>
   * Multi-fire: every call emits an event.
   *
   * @param sourceKey the key of the source node
   * @param redirectedTarget the key of the node that was actually used
   */
  public void trackRedirect(String sourceKey, String redirectedTarget) {
    LDValue data = baseData()
        .put("sourceKey", sourceKey)
        .put("redirectedTarget", redirectedTarget)
        .build();
    client.trackMetric(GRAPH_REDIRECT, context, data, 1);
  }

  /**
   * Records a successful handoff from one node to another.
   * <p>
   * Multi-fire: every call emits an event.
   *
   * @param sourceKey the key of the source node
   * @param targetKey the key of the target node
   */
  public void trackHandoffSuccess(String sourceKey, String targetKey) {
    LDValue data = baseData()
        .put("sourceKey", sourceKey)
        .put("targetKey", targetKey)
        .build();
    client.trackMetric(GRAPH_HANDOFF_SUCCESS, context, data, 1);
  }

  /**
   * Records a failed handoff from one node to another.
   * <p>
   * Multi-fire: every call emits an event.
   *
   * @param sourceKey the key of the source node
   * @param targetKey the key of the target node
   */
  public void trackHandoffFailure(String sourceKey, String targetKey) {
    LDValue data = baseData()
        .put("sourceKey", sourceKey)
        .put("targetKey", targetKey)
        .build();
    client.trackMetric(GRAPH_HANDOFF_FAILURE, context, data, 1);
  }

  /**
   * Returns a snapshot of all graph-level metrics tracked so far on this tracker.
   *
   * @return the metric summary; never {@code null}
   */
  public AIGraphMetricSummary getSummary() {
    return new AIGraphMetricSummary(
        invocationRecorded.get(),
        durationRecorded.get(),
        tokensRecorded.get(),
        pathRecorded.get(),
        resumptionToken);
  }

  /**
   * Returns the resumption token for this graph run.
   * <p>
   * The token encodes the run identity and can be passed to
   * {@link LDAIClient#createGraphTracker(String, LDContext)} to reconstruct the tracker across
   * requests.
   *
   * @return the resumption token; never {@code null}
   */
  public String getResumptionToken() {
    return resumptionToken;
  }

  private ObjectBuilder baseData() {
    ObjectBuilder b = LDValue.buildObject()
        .put("runId", runId)
        .put("graphKey", graphKey)
        .put("version", version);
    if (variationKey != null) {
      b.put("variationKey", variationKey);
    }
    return b;
  }

  private static LDLogger defaultLogger() {
    LDLogAdapter adapter;
    try {
      Class.forName("org.slf4j.LoggerFactory");
      adapter = LDSLF4J.adapter();
    } catch (ClassNotFoundException e) {
      adapter = Logs.toConsole();
    }
    return LDLogger.withAdapter(adapter, "LaunchDarkly.AI");
  }
}
