package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.EvaluationReason.ErrorKind;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.server.DataModel.FeatureFlag;
import com.launchdarkly.sdk.server.DataModel.Segment;
import com.launchdarkly.sdk.server.EvaluatorTestUtil.EvaluatorBuilder;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static com.launchdarkly.sdk.server.EvaluatorTestUtil.BASE_USER;
import static com.launchdarkly.sdk.server.EvaluatorTestUtil.FALLTHROUGH_VALUE;
import static com.launchdarkly.sdk.server.EvaluatorTestUtil.FALLTHROUGH_VARIATION;
import static com.launchdarkly.sdk.server.EvaluatorTestUtil.GREEN_VARIATION;
import static com.launchdarkly.sdk.server.EvaluatorTestUtil.MATCH_VALUE;
import static com.launchdarkly.sdk.server.EvaluatorTestUtil.MATCH_VARIATION;
import static com.launchdarkly.sdk.server.EvaluatorTestUtil.OFF_VALUE;
import static com.launchdarkly.sdk.server.EvaluatorTestUtil.OFF_VARIATION;
import static com.launchdarkly.sdk.server.EvaluatorTestUtil.buildRedGreenFlag;
import static com.launchdarkly.sdk.server.EvaluatorTestUtil.buildThreeWayFlag;
import static com.launchdarkly.sdk.server.EvaluatorTestUtil.evaluatorBuilder;
import static com.launchdarkly.sdk.server.ModelBuilders.clause;
import static com.launchdarkly.sdk.server.ModelBuilders.clauseMatchingSegment;
import static com.launchdarkly.sdk.server.ModelBuilders.flagBuilder;
import static com.launchdarkly.sdk.server.ModelBuilders.negateClause;
import static com.launchdarkly.sdk.server.ModelBuilders.prerequisite;
import static com.launchdarkly.sdk.server.ModelBuilders.ruleBuilder;
import static com.launchdarkly.sdk.server.ModelBuilders.segmentBuilder;
import static com.launchdarkly.sdk.server.ModelBuilders.segmentRuleBuilder;
import static com.launchdarkly.sdk.server.ModelBuilders.target;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * An evaluation is override-affected when any definition it read carries the override marker: the
 * evaluated flag, a prerequisite at any depth, or a segment consulted during matching. The marking
 * propagates upward only. A prerequisite's own record reflects only its own subtree.
 */
@SuppressWarnings("javadoc")
public class EvaluatorOverrideMarkingTest {
  private static final LDContext OTHER_USER = LDContext.create("other");

  private static final class RecordingRecorder implements EvaluationRecorder {
    final List<PrerequisiteEvalRecord> prerequisites = new ArrayList<>();

    @Override
    public void recordPrerequisiteEvaluation(FeatureFlag flag, FeatureFlag prereqOfFlag, LDContext context, EvalResult result) {
      prerequisites.add(new PrerequisiteEvalRecord(flag, prereqOfFlag, result));
    }
  }

  private static PrerequisiteEvalRecord recordFor(List<PrerequisiteEvalRecord> records, String flagKey) {
    for (PrerequisiteEvalRecord r : records) {
      if (r.flag.getKey().equals(flagKey)) {
        return r;
      }
    }
    throw new AssertionError("no prerequisite record for " + flagKey);
  }

  @Test
  public void evaluationOfUnmarkedFlagIsNotOverrideAffected() {
    FeatureFlag f = buildThreeWayFlag("feature").on(true).build();
    EvalResult result = evaluatorBuilder().build().evaluate(f, BASE_USER, new EvaluationRecorder() {});

    assertFalse(result.isOverrideAffected());
    assertFalse(result.getReason().isOverrideAffected());
    assertEquals(EvaluationReason.fallthrough(), result.getReason());
  }

  @Test
  public void evaluationOfOverrideFlagIsMarkedForEveryReasonKind() {
    Evaluator e = evaluatorBuilder().build();

    FeatureFlag off = buildThreeWayFlag("off").on(false).build().markedAsOverride();
    EvalResult offResult = e.evaluate(off, BASE_USER, new EvaluationRecorder() {});
    assertEquals(EvalResult.of(OFF_VALUE, OFF_VARIATION, EvaluationReason.off().withOverrideAffected(true)), offResult);
    assertTrue(offResult.isOverrideAffected());

    FeatureFlag fallthrough = buildThreeWayFlag("fallthrough").on(true).build().markedAsOverride();
    assertEquals(EvaluationReason.fallthrough().withOverrideAffected(true),
        e.evaluate(fallthrough, BASE_USER, new EvaluationRecorder() {}).getReason());

    FeatureFlag targeted = buildThreeWayFlag("target").on(true)
        .targets(target(MATCH_VARIATION, BASE_USER.getKey())).build().markedAsOverride();
    assertEquals(EvaluationReason.targetMatch().withOverrideAffected(true),
        e.evaluate(targeted, BASE_USER, new EvaluationRecorder() {}).getReason());

    FeatureFlag ruled = buildThreeWayFlag("rule").on(true)
        .rules(ruleBuilder().id("r").variation(MATCH_VARIATION)
            .clauses(clause("key", DataModel.Operator.in, LDValue.of(BASE_USER.getKey()))).build())
        .build().markedAsOverride();
    EvalResult ruleResult = e.evaluate(ruled, BASE_USER, new EvaluationRecorder() {});
    assertEquals(EvaluationReason.ruleMatch(0, "r").withOverrideAffected(true), ruleResult.getReason());
    assertEquals(MATCH_VALUE, ruleResult.getValue());
  }

  @Test
  public void evaluationErrorOfOverrideFlagIsMarked() {
    // The fallthrough points at a variation that does not exist, which is a malformed flag.
    FeatureFlag malformed = flagBuilder("malformed").on(true).variations(LDValue.of("only"))
        .fallthroughVariation(5).offVariation(0).build().markedAsOverride();
    EvalResult result = evaluatorBuilder().build().evaluate(malformed, BASE_USER, new EvaluationRecorder() {});

    assertEquals(EvaluationReason.error(ErrorKind.MALFORMED_FLAG).withOverrideAffected(true), result.getReason());
    assertTrue(result.isNoVariation());
    assertTrue(result.isOverrideAffected());
  }

  @Test
  public void thrownEvaluationErrorOfOverrideFlagIsMarked() {
    // A clause without an attribute makes the evaluator throw, which becomes an error result.
    FeatureFlag broken = buildThreeWayFlag("broken").on(true)
        .rules(ruleBuilder().id("r").variation(MATCH_VARIATION)
            .clauses(clause(null, DataModel.Operator.in, LDValue.of("x"))).build())
        .build().markedAsOverride();
    EvalResult result = evaluatorBuilder().build().evaluate(broken, BASE_USER, new EvaluationRecorder() {});

    assertEquals(EvaluationReason.error(ErrorKind.MALFORMED_FLAG).withOverrideAffected(true), result.getReason());
  }

  @Test
  public void overridePrerequisiteMarksParentAndItsOwnRecord() {
    FeatureFlag parent = buildThreeWayFlag("parent").on(true)
        .prerequisites(prerequisite("prereq", GREEN_VARIATION)).build();
    FeatureFlag prereq = buildRedGreenFlag("prereq").on(true).build().markedAsOverride();
    RecordingRecorder recorder = new RecordingRecorder();

    EvalResult result = evaluatorBuilder().withStoredFlags(prereq).build().evaluate(parent, BASE_USER, recorder);

    assertEquals(FALLTHROUGH_VALUE, result.getValue());
    assertEquals(EvaluationReason.fallthrough().withOverrideAffected(true), result.getReason());
    assertTrue(result.isOverrideAffected());

    PrerequisiteEvalRecord record = recordFor(recorder.prerequisites, "prereq");
    assertTrue(record.result.isOverrideAffected());
    assertEquals(EvaluationReason.fallthrough().withOverrideAffected(true), record.result.getReason());
    // The same record is on the result.
    assertTrue(recordFor(result.getPrerequisiteEvalRecords(), "prereq").result.isOverrideAffected());
  }

  @Test
  public void failedOverridePrerequisiteStillMarksParent() {
    FeatureFlag parent = buildThreeWayFlag("parent").on(true)
        .prerequisites(prerequisite("prereq", GREEN_VARIATION)).build();
    FeatureFlag prereq = buildRedGreenFlag("prereq").on(false).build().markedAsOverride();

    EvalResult result = evaluatorBuilder().withStoredFlags(prereq).build()
        .evaluate(parent, BASE_USER, new EvaluationRecorder() {});

    assertEquals(OFF_VALUE, result.getValue());
    assertEquals(EvaluationReason.prerequisiteFailed("prereq").withOverrideAffected(true), result.getReason());
  }

  @Test
  public void unaffectedPrerequisiteRecordIsNotMarkedInsideMarkedEvaluation() {
    FeatureFlag parent = buildThreeWayFlag("parent").on(true)
        .prerequisites(prerequisite("overridden", GREEN_VARIATION), prerequisite("plain", GREEN_VARIATION)).build();
    FeatureFlag overridden = buildRedGreenFlag("overridden").on(true).build().markedAsOverride();
    FeatureFlag plain = buildRedGreenFlag("plain").on(true).build();
    RecordingRecorder recorder = new RecordingRecorder();

    EvalResult result = evaluatorBuilder().withStoredFlags(overridden, plain).build().evaluate(parent, BASE_USER, recorder);

    assertTrue(result.isOverrideAffected());
    assertTrue(recordFor(recorder.prerequisites, "overridden").result.isOverrideAffected());
    assertFalse(recordFor(recorder.prerequisites, "plain").result.isOverrideAffected());
    assertEquals(EvaluationReason.fallthrough(), recordFor(recorder.prerequisites, "plain").result.getReason());
  }

  @Test
  public void markingPropagatesUpwardThroughEveryDepthButNotSideways() {
    // A depends on B and C. B depends on D, which is the only override. A, B, and D are marked.
    // C is not.
    FeatureFlag a = buildThreeWayFlag("a").on(true)
        .prerequisites(prerequisite("b", GREEN_VARIATION), prerequisite("c", GREEN_VARIATION)).build();
    FeatureFlag b = buildRedGreenFlag("b").on(true).prerequisites(prerequisite("d", GREEN_VARIATION)).build();
    FeatureFlag c = buildRedGreenFlag("c").on(true).build();
    FeatureFlag d = buildRedGreenFlag("d").on(true).build().markedAsOverride();
    RecordingRecorder recorder = new RecordingRecorder();

    EvalResult result = evaluatorBuilder().withStoredFlags(b, c, d).build().evaluate(a, BASE_USER, recorder);

    assertTrue(result.isOverrideAffected());
    assertTrue(recordFor(recorder.prerequisites, "b").result.isOverrideAffected());
    assertTrue(recordFor(recorder.prerequisites, "d").result.isOverrideAffected());
    assertFalse(recordFor(recorder.prerequisites, "c").result.isOverrideAffected());
  }

  @Test
  public void overrideFlagWithUnmarkedPrerequisiteMarksOnlyItself() {
    FeatureFlag parent = buildThreeWayFlag("parent").on(true)
        .prerequisites(prerequisite("prereq", GREEN_VARIATION)).build().markedAsOverride();
    FeatureFlag prereq = buildRedGreenFlag("prereq").on(true).build();
    RecordingRecorder recorder = new RecordingRecorder();

    EvalResult result = evaluatorBuilder().withStoredFlags(prereq).build().evaluate(parent, BASE_USER, recorder);

    assertTrue(result.isOverrideAffected());
    // The parent's marking does not flow down into the prerequisite's record.
    assertFalse(recordFor(recorder.prerequisites, "prereq").result.isOverrideAffected());
  }

  @Test
  public void circularReferenceThroughOverridePrerequisiteIsMarkedError() {
    FeatureFlag a = buildThreeWayFlag("a").on(true).prerequisites(prerequisite("b", GREEN_VARIATION)).build();
    FeatureFlag b = buildRedGreenFlag("b").on(true).prerequisites(prerequisite("a", GREEN_VARIATION)).build()
        .markedAsOverride();

    EvalResult result = evaluatorBuilder().withStoredFlags(b).build().evaluate(a, BASE_USER, new EvaluationRecorder() {});

    assertEquals(EvaluationReason.error(ErrorKind.MALFORMED_FLAG).withOverrideAffected(true), result.getReason());
  }

  @Test
  public void matchingOverrideSegmentMarksEvaluation() {
    Segment segment = segmentBuilder("seg").included(BASE_USER.getKey()).build().markedAsOverride();
    FeatureFlag f = buildThreeWayFlag("feature").on(true)
        .rules(ruleBuilder().id("r").variation(MATCH_VARIATION).clauses(clauseMatchingSegment("seg")).build())
        .build();

    EvalResult result = evaluatorBuilder().withStoredSegments(segment).build()
        .evaluate(f, BASE_USER, new EvaluationRecorder() {});

    assertEquals(MATCH_VALUE, result.getValue());
    assertEquals(EvaluationReason.ruleMatch(0, "r").withOverrideAffected(true), result.getReason());
  }

  @Test
  public void nonMatchingOverrideSegmentStillMarksEvaluation() {
    Segment segment = segmentBuilder("seg").included(BASE_USER.getKey()).build().markedAsOverride();
    FeatureFlag f = buildThreeWayFlag("feature").on(true)
        .rules(ruleBuilder().id("r").variation(MATCH_VARIATION).clauses(clauseMatchingSegment("seg")).build())
        .build();

    EvalResult result = evaluatorBuilder().withStoredSegments(segment).build()
        .evaluate(f, OTHER_USER, new EvaluationRecorder() {});

    assertEquals(FALLTHROUGH_VALUE, result.getValue());
    assertEquals(EvaluationReason.fallthrough().withOverrideAffected(true), result.getReason());
  }

  @Test
  public void negatedClauseOnOverrideSegmentMarksEvaluation() {
    Segment segment = segmentBuilder("seg").included(BASE_USER.getKey()).build().markedAsOverride();
    FeatureFlag f = buildThreeWayFlag("feature").on(true)
        .rules(ruleBuilder().id("r").variation(MATCH_VARIATION)
            .clauses(negateClause(clauseMatchingSegment("seg"))).build())
        .build();

    EvalResult result = evaluatorBuilder().withStoredSegments(segment).build()
        .evaluate(f, OTHER_USER, new EvaluationRecorder() {});

    assertEquals(MATCH_VALUE, result.getValue());
    assertTrue(result.isOverrideAffected());
  }

  @Test
  public void unmarkedSegmentDoesNotMarkEvaluation() {
    Segment segment = segmentBuilder("seg").included(BASE_USER.getKey()).build();
    FeatureFlag f = buildThreeWayFlag("feature").on(true)
        .rules(ruleBuilder().id("r").variation(MATCH_VARIATION).clauses(clauseMatchingSegment("seg")).build())
        .build();

    EvalResult result = evaluatorBuilder().withStoredSegments(segment).build()
        .evaluate(f, BASE_USER, new EvaluationRecorder() {});

    assertEquals(MATCH_VALUE, result.getValue());
    assertFalse(result.isOverrideAffected());
  }

  @Test
  public void overrideSegmentReferencedByAnotherSegmentMarksEvaluation() {
    Segment inner = segmentBuilder("inner").included(BASE_USER.getKey()).build().markedAsOverride();
    Segment outer = segmentBuilder("outer")
        .rules(segmentRuleBuilder().clauses(clauseMatchingSegment("inner")).build()).build();
    FeatureFlag f = buildThreeWayFlag("feature").on(true)
        .rules(ruleBuilder().id("r").variation(MATCH_VARIATION).clauses(clauseMatchingSegment("outer")).build())
        .build();

    EvalResult result = evaluatorBuilder().withStoredSegments(inner, outer).build()
        .evaluate(f, BASE_USER, new EvaluationRecorder() {});

    assertEquals(MATCH_VALUE, result.getValue());
    assertTrue(result.isOverrideAffected());
  }

  @Test
  public void segmentReadByPrerequisiteMarksPrerequisiteAndParent() {
    Segment segment = segmentBuilder("seg").included(BASE_USER.getKey()).build().markedAsOverride();
    FeatureFlag parent = buildThreeWayFlag("parent").on(true)
        .prerequisites(prerequisite("prereq", GREEN_VARIATION)).build();
    FeatureFlag prereq = buildRedGreenFlag("prereq").on(true)
        .rules(ruleBuilder().id("r").variation(GREEN_VARIATION).clauses(clauseMatchingSegment("seg")).build())
        .build();
    RecordingRecorder recorder = new RecordingRecorder();

    EvalResult result = evaluatorBuilder().withStoredFlags(prereq).withStoredSegments(segment).build()
        .evaluate(parent, BASE_USER, recorder);

    assertTrue(result.isOverrideAffected());
    assertTrue(recordFor(recorder.prerequisites, "prereq").result.isOverrideAffected());
  }

  @Test
  public void markedEvaluationDoesNotAlterSharedPrecomputedResults() {
    FeatureFlag source = buildThreeWayFlag("feature").on(false).build();
    FeatureFlag marked = source.markedAsOverride();
    Evaluator e = evaluatorBuilder().build();

    EvalResult markedResult = e.evaluate(marked, BASE_USER, new EvaluationRecorder() {});
    EvalResult sourceResult = e.evaluate(source, BASE_USER, new EvaluationRecorder() {});

    assertTrue(markedResult.isOverrideAffected());
    assertFalse(sourceResult.isOverrideAffected());
    assertFalse(source.isOverride());
    assertNotSame(markedResult, sourceResult);
    // The unmarked evaluation still returns the shared precomputed instance.
    assertSame(sourceResult, e.evaluate(source, BASE_USER, new EvaluationRecorder() {}));
  }

  @Test
  public void bigSegmentsStatusAndOverrideMarkingAreBothKept() {
    Segment bigSegment = segmentBuilder("big").unbounded(true).generation(1).build().markedAsOverride();
    FeatureFlag f = buildThreeWayFlag("feature").on(true)
        .rules(ruleBuilder().id("r").variation(MATCH_VARIATION).clauses(clauseMatchingSegment("big")).build())
        .build();
    // No big segment store is configured, so the status is NOT_CONFIGURED.
    EvaluatorBuilder builder = evaluatorBuilder().withStoredSegments(bigSegment)
        .withBigSegmentQueryResult(BASE_USER.getKey(), null);

    EvalResult result = builder.build().evaluate(f, BASE_USER, new EvaluationRecorder() {});

    assertEquals(FALLTHROUGH_VARIATION, result.getVariationIndex());
    assertEquals(EvaluationReason.BigSegmentsStatus.NOT_CONFIGURED, result.getReason().getBigSegmentsStatus());
    assertTrue(result.getReason().isOverrideAffected());
  }
}
