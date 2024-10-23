package com.launchdarkly.sdk.server;

import com.google.common.collect.Iterables;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.EvaluationReason.ErrorKind;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.server.DataModel.FeatureFlag;
import com.launchdarkly.sdk.server.DataModel.Prerequisite;
import org.junit.Test;

import static com.launchdarkly.sdk.server.EvaluatorTestUtil.BASE_USER;
import static com.launchdarkly.sdk.server.EvaluatorTestUtil.FALLTHROUGH_VALUE;
import static com.launchdarkly.sdk.server.EvaluatorTestUtil.FALLTHROUGH_VARIATION;
import static com.launchdarkly.sdk.server.EvaluatorTestUtil.GREEN_VALUE;
import static com.launchdarkly.sdk.server.EvaluatorTestUtil.GREEN_VARIATION;
import static com.launchdarkly.sdk.server.EvaluatorTestUtil.OFF_VALUE;
import static com.launchdarkly.sdk.server.EvaluatorTestUtil.OFF_VARIATION;
import static com.launchdarkly.sdk.server.EvaluatorTestUtil.RED_VALUE;
import static com.launchdarkly.sdk.server.EvaluatorTestUtil.RED_VARIATION;
import static com.launchdarkly.sdk.server.EvaluatorTestUtil.buildRedGreenFlag;
import static com.launchdarkly.sdk.server.EvaluatorTestUtil.buildThreeWayFlag;
import static com.launchdarkly.sdk.server.EvaluatorTestUtil.evaluatorBuilder;
import static com.launchdarkly.sdk.server.EvaluatorTestUtil.expectNoPrerequisiteEvals;
import static com.launchdarkly.sdk.server.ModelBuilders.flagBuilder;
import static com.launchdarkly.sdk.server.ModelBuilders.prerequisite;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

@SuppressWarnings("javadoc")
public class EvaluatorPrerequisiteTest {
  @Test
  public void flagReturnsOffVariationIfPrerequisiteIsNotFound() throws Exception {
    FeatureFlag f0 = buildThreeWayFlag("feature")
        .on(true)
        .prerequisites(prerequisite("feature1", 1))
        .build();
    Evaluator e = evaluatorBuilder().withNonexistentFlag("feature1").build();
    EvalResult result = e.evaluate(f0, BASE_USER, expectNoPrerequisiteEvals());
    
    EvaluationReason expectedReason = EvaluationReason.prerequisiteFailed("feature1");
    assertEquals(EvalResult.of(OFF_VALUE, OFF_VARIATION, expectedReason), result);
    assertEquals(0, result.getPrerequisiteEvalRecords().size());
  }

  @Test
  public void flagReturnsOffVariationAndEventIfPrerequisiteIsOff() throws Exception {
    FeatureFlag f0 = buildThreeWayFlag("feature")
        .on(true)
        .prerequisites(prerequisite("feature1", GREEN_VARIATION))
        .build();
    FeatureFlag f1 = buildRedGreenFlag("feature1")
        .on(false)
        .offVariation(GREEN_VARIATION)
        // note that even though it returns the desired variation, it is still off and therefore not a match
        .build();
    Evaluator e = evaluatorBuilder().withStoredFlags(f1).build();
    EvalResult result = e.evaluate(f0, BASE_USER, new EvaluationRecorder(){});
    
    EvaluationReason expectedReason = EvaluationReason.prerequisiteFailed("feature1");
    assertEquals(EvalResult.of(OFF_VALUE, OFF_VARIATION, expectedReason), result);
    
    assertEquals(1, Iterables.size(result.getPrerequisiteEvalRecords()));
    PrerequisiteEvalRecord eval = result.getPrerequisiteEvalRecords().get(0);
    assertEquals(f1, eval.flag);
    assertEquals(f0, eval.prereqOfFlag);
    assertEquals(GREEN_VARIATION, eval.result.getVariationIndex());
    assertEquals(GREEN_VALUE, eval.result.getValue());
    assertEquals(1, result.getPrerequisiteEvalRecords().size());
  }

  @Test
  public void flagReturnsOffVariationAndEventIfPrerequisiteIsNotMet() throws Exception {
    FeatureFlag f0 = buildThreeWayFlag("feature")
        .on(true)
        .prerequisites(prerequisite("feature1", GREEN_VARIATION))
        .build();
    FeatureFlag f1 = buildRedGreenFlag("feature1")
        .on(true)
        .fallthroughVariation(RED_VARIATION)
        .build();
    Evaluator e = evaluatorBuilder().withStoredFlags(f1).build();
    EvalResult result = e.evaluate(f0, BASE_USER, new EvaluationRecorder(){});
    
    EvaluationReason expectedReason = EvaluationReason.prerequisiteFailed("feature1");
    assertEquals(EvalResult.of(OFF_VALUE, OFF_VARIATION, expectedReason), result);

    assertEquals(1, Iterables.size(result.getPrerequisiteEvalRecords()));
    PrerequisiteEvalRecord eval = result.getPrerequisiteEvalRecords().get(0);
    assertEquals(f1, eval.flag);
    assertEquals(f0, eval.prereqOfFlag);
    assertEquals(RED_VARIATION, eval.result.getVariationIndex());
    assertEquals(RED_VALUE, eval.result.getValue());
    assertEquals(1, result.getPrerequisiteEvalRecords().size());
  }

  @Test
  public void prerequisiteFailedResultInstanceIsReusedForSamePrerequisite() throws Exception {
    FeatureFlag f0 = buildThreeWayFlag("feature")
        .on(true)
        .prerequisites(prerequisite("feature1", GREEN_VARIATION))
        .build();
    Evaluator e = evaluatorBuilder().withNonexistentFlag("feature1").build();
    EvalResult result0 = e.evaluate(f0, BASE_USER, expectNoPrerequisiteEvals());
    EvalResult result1 = e.evaluate(f0, BASE_USER, expectNoPrerequisiteEvals());
    
    EvaluationReason expectedReason = EvaluationReason.prerequisiteFailed("feature1");
    assertEquals(expectedReason, result0.getReason());
    assertSame(result0, result1);
  }

  @Test
  public void prerequisiteFailedReasonInstanceCanBeCreatedFromScratch() throws Exception {
    // Normally we will always do the preprocessing step that creates the reason instances ahead of time,
    // but if somehow we didn't, it should create them as needed
    FeatureFlag f0 = buildThreeWayFlag("feature")
        .on(true)
        .prerequisites(prerequisite("feature1", GREEN_VARIATION))
        .disablePreprocessing(true)
        .build();
    assertNull(f0.getPrerequisites().get(0).preprocessed);
    
    Evaluator e = evaluatorBuilder().withNonexistentFlag("feature1").build();
    EvalResult result0 = e.evaluate(f0, BASE_USER, expectNoPrerequisiteEvals());
    EvalResult result1 = e.evaluate(f0, BASE_USER, expectNoPrerequisiteEvals());
    
    EvaluationReason expectedReason = EvaluationReason.prerequisiteFailed("feature1");
    assertEquals(expectedReason, result0.getReason());
    assertNotSame(result0.getReason(), result1.getReason()); // they were created individually
    assertEquals(result0.getReason(), result1.getReason()); // but they're equal
  }

  @Test
  public void flagReturnsFallthroughVariationAndEventIfPrerequisiteIsMetAndThereAreNoRules() throws Exception {
    FeatureFlag f0 = buildThreeWayFlag("feature")
        .on(true)
        .prerequisites(prerequisite("feature1", GREEN_VARIATION))
        .build();
    FeatureFlag f1 = buildRedGreenFlag("feature1")
        .on(true)
        .fallthroughVariation(GREEN_VARIATION)
        .version(2)
        .build();
    Evaluator e = evaluatorBuilder().withStoredFlags(f1).build();
    EvalResult result = e.evaluate(f0, BASE_USER, new EvaluationRecorder(){});
    
    assertEquals(EvalResult.of(FALLTHROUGH_VALUE, FALLTHROUGH_VARIATION, EvaluationReason.fallthrough()), result);

    assertEquals(1, Iterables.size(result.getPrerequisiteEvalRecords()));
    PrerequisiteEvalRecord eval = result.getPrerequisiteEvalRecords().get(0);
    assertEquals(f1, eval.flag);
    assertEquals(f0, eval.prereqOfFlag);
    assertEquals(GREEN_VARIATION, eval.result.getVariationIndex());
    assertEquals(GREEN_VALUE, eval.result.getValue());
  }

  @Test
  public void multipleLevelsOfPrerequisitesProduceMultipleEvents() throws Exception {
    FeatureFlag f0 = buildThreeWayFlag("feature")
        .on(true)
        .prerequisites(prerequisite("feature1", GREEN_VARIATION))
        .build();
    FeatureFlag f1 = buildRedGreenFlag("feature1")
        .on(true)
        .prerequisites(prerequisite("feature2", GREEN_VARIATION))
        .fallthroughVariation(GREEN_VARIATION)
        .build();
    FeatureFlag f2 = buildRedGreenFlag("feature2")
        .on(true)
        .fallthroughVariation(GREEN_VARIATION)
        .build();
    Evaluator e = evaluatorBuilder().withStoredFlags(f1, f2).build();
    EvalResult result = e.evaluate(f0, BASE_USER, new EvaluationRecorder(){});
    
    assertEquals(EvalResult.of(FALLTHROUGH_VALUE, FALLTHROUGH_VARIATION, EvaluationReason.fallthrough()), result);

    assertEquals(2, Iterables.size(result.getPrerequisiteEvalRecords()));
    
    PrerequisiteEvalRecord eval0 = result.getPrerequisiteEvalRecords().get(0);
    assertEquals(f2, eval0.flag);
    assertEquals(f1, eval0.prereqOfFlag);
    assertEquals(GREEN_VARIATION, eval0.result.getVariationIndex());
    assertEquals(GREEN_VALUE, eval0.result.getValue());

    PrerequisiteEvalRecord eval1 = result.getPrerequisiteEvalRecords().get(1);
    assertEquals(f1, eval1.flag);
    assertEquals(f0, eval1.prereqOfFlag);
    assertEquals(GREEN_VARIATION, eval1.result.getVariationIndex());
    assertEquals(GREEN_VALUE, eval1.result.getValue());
  }

  @Test
  public void prerequisitesListIsAccurateWhenShortCircuiting() throws Exception {
    FeatureFlag f0 = buildThreeWayFlag("feature")
        .on(true)
        .prerequisites(prerequisite("prereq1", GREEN_VARIATION), prerequisite("prereq2", GREEN_VARIATION), prerequisite("prereq3", GREEN_VARIATION))
        .build();
    FeatureFlag f1 = buildRedGreenFlag("prereq1")
        .on(true)
        .fallthroughVariation(GREEN_VARIATION)
        .build();
    FeatureFlag f2 = buildRedGreenFlag("prereq2")
        .on(true)
        .fallthroughVariation(RED_VARIATION)
        .build();
    FeatureFlag f3 = buildRedGreenFlag("prereq3")
        .on(true)
        .fallthroughVariation(GREEN_VARIATION)
        .build();

    Evaluator e = evaluatorBuilder().withStoredFlags(f0, f1, f2, f3).build();
    EvalResult result = e.evaluate(f0, BASE_USER, new EvaluationRecorder(){});

    assertEquals(EvalResult.of(OFF_VALUE, OFF_VARIATION, EvaluationReason.prerequisiteFailed("prereq2")), result);
    assertEquals(2, Iterables.size(result.getPrerequisiteEvalRecords())); // prereq 1 and 2 are reached, but 2 fails, so 3 is not checked.

    PrerequisiteEvalRecord prereq1Eval = result.getPrerequisiteEvalRecords().get(0);
    assertEquals(f1, prereq1Eval.flag);
    assertEquals(f0, prereq1Eval.prereqOfFlag);
    assertEquals(GREEN_VARIATION, prereq1Eval.result.getVariationIndex());
    assertEquals(GREEN_VALUE, prereq1Eval.result.getValue());

    PrerequisiteEvalRecord prereq2Eval = result.getPrerequisiteEvalRecords().get(1);
    assertEquals(f2, prereq2Eval.flag);
    assertEquals(f0, prereq2Eval.prereqOfFlag);
    assertEquals(RED_VARIATION, prereq2Eval.result.getVariationIndex());
    assertEquals(RED_VALUE, prereq2Eval.result.getValue());
  }

  @Test
  public void prerequisiteCycleDetection() {
    for (int depth = 1; depth <= 4; depth++) {
      String[] flagKeys = new String[depth];
      for (int i = 0; i < depth; i++) {
        flagKeys[i] = "flagkey" + i;
      }
      FeatureFlag[] flags = new FeatureFlag[depth];
      for (int i = 0; i < depth; i++) {
        flags[i] = flagBuilder(flagKeys[i])
            .on(true)
            .variations(false, true)
            .offVariation(0)
            .prerequisites(
                new Prerequisite(flagKeys[(i + 1) % depth], 0)
                )
            .build();
      }

      Evaluator e = evaluatorBuilder().withStoredFlags(flags).build();

      LDContext context = LDContext.create("foo");
      EvalResult result = e.evaluate(flags[0], context, expectNoPrerequisiteEvals());
      assertEquals(EvalResult.error(ErrorKind.MALFORMED_FLAG), result);
      // Note, we specified expectNoPrerequisiteEvals() above because we do not expect the evaluator
      // to *finish* evaluating any of these prerequisites (it can't, because of the cycle), and so
      // it won't get as far as emitting any prereq evaluation results. 
    }
  }
}
