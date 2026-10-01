package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.ContextKind;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.server.DataModel.FeatureFlag;
import com.launchdarkly.sdk.server.DataModel.Rollout;
import com.launchdarkly.sdk.server.DataModel.RolloutKind;
import com.launchdarkly.sdk.server.DataModel.WeightedVariation;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static com.launchdarkly.sdk.server.EvaluatorBucketing.computeBucketValue;
import static com.launchdarkly.sdk.server.EvaluatorTestUtil.BASE_EVALUATOR;
import static com.launchdarkly.sdk.server.EvaluatorTestUtil.expectNoPrerequisiteEvals;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;

/**
 * Regression tests for https://github.com/launchdarkly/java-core/issues/94: bucketing must use
 * double-precision arithmetic, and rollout bucket boundaries must be derived from integer weight
 * sums rather than an accumulated floating-point sum. Otherwise, rounding errors can shift bucket
 * boundaries differently for different flags, breaking mutual exclusivity of experiments that
 * share a layer.
 */
@SuppressWarnings("javadoc")
public class EvaluatorBucketingPrecisionTest {
  private static final Integer SEED = 682385145;
  private static final String CONTEXT_KEY = "2937330902736791534808";

  // Realistic experiment data from the issue report: 551 weighted variations whose weights sum
  // to exactly 100000. Each row is {variation, weight, untracked}. For the context key above,
  // the bucket value lands just below a bucket boundary, so any drift in the computed boundary
  // assigns the wrong variation.
  private static final int[][] WEIGHT_DATA = {
      {1, 93, 1}, {0, 5, 0}, {1, 281, 1}, {0, 81, 0}, {1, 227, 1}, {0, 100, 0}, {1, 998, 1}, {0, 100, 0}, {1, 90, 1}, {0, 100, 0}, {1, 22, 1}, {0, 100, 0},
      {1, 114, 1}, {1, 23, 0}, {1, 185, 1}, {1, 22, 0}, {0, 100, 0}, {1, 578, 1}, {0, 24, 0}, {1, 540, 1}, {0, 51, 0}, {1, 163, 1}, {1, 100, 0}, {1, 279, 1},
      {1, 2, 0}, {1, 500, 1}, {0, 100, 0}, {1, 498, 1}, {1, 56, 0}, {1, 210, 1}, {1, 4, 0}, {1, 210, 1}, {1, 100, 0}, {1, 735, 1}, {1, 100, 0}, {1, 69, 1},
      {1, 69, 0}, {1, 600, 1}, {1, 100, 0}, {1, 442, 1}, {1, 24, 0}, {0, 24, 0}, {1, 310, 1}, {0, 23, 0}, {1, 216, 1}, {0, 100, 0}, {1, 92, 1}, {1, 56, 0},
      {1, 100, 0}, {1, 181, 1}, {0, 59, 0}, {1, 727, 1}, {0, 100, 0}, {1, 17, 1}, {0, 66, 0}, {1, 394, 1}, {1, 32, 0}, {1, 139, 1}, {0, 92, 0}, {1, 155, 1},
      {0, 56, 0}, {1, 146, 1}, {1, 5, 0}, {1, 150, 1}, {1, 60, 0}, {1, 12, 0}, {1, 151, 1}, {1, 100, 0}, {1, 116, 1}, {1, 100, 0}, {1, 147, 1}, {0, 100, 0},
      {1, 1591, 1}, {0, 68, 0}, {1, 290, 1}, {0, 17, 0}, {1, 163, 1}, {0, 20, 0}, {1, 120, 1}, {1, 39, 0}, {1, 85, 0}, {1, 181, 1}, {0, 100, 0}, {1, 16, 1},
      {0, 78, 0}, {1, 548, 1}, {0, 23, 0}, {1, 314, 1}, {1, 100, 0}, {1, 312, 1}, {1, 40, 0}, {1, 257, 1}, {1, 72, 0}, {1, 561, 1}, {1, 54, 0}, {1, 572, 1},
      {1, 100, 0}, {1, 86, 1}, {1, 59, 0}, {0, 48, 0}, {1, 466, 1}, {1, 91, 0}, {1, 836, 1}, {1, 15, 0}, {1, 206, 1}, {0, 100, 0}, {1, 1058, 1}, {1, 100, 0},
      {1, 395, 1}, {0, 20, 0}, {1, 307, 1}, {0, 26, 0}, {1, 317, 1}, {1, 100, 0}, {1, 185, 1}, {1, 100, 0}, {1, 74, 1}, {1, 100, 0}, {1, 26, 1}, {0, 39, 0},
      {0, 100, 0}, {1, 499, 1}, {1, 16, 0}, {1, 138, 1}, {0, 13, 0}, {1, 774, 1}, {1, 100, 0}, {1, 43, 1}, {1, 4, 0}, {1, 498, 1}, {1, 100, 0}, {1, 155, 1},
      {1, 40, 0}, {1, 73, 0}, {1, 480, 1}, {1, 16, 0}, {1, 304, 1}, {0, 19, 0}, {1, 158, 1}, {0, 100, 0}, {1, 29, 1}, {1, 100, 0}, {1, 125, 1}, {0, 100, 0},
      {1, 194, 1}, {1, 24, 0}, {1, 554, 1}, {0, 36, 0}, {0, 5, 0}, {1, 101, 1}, {1, 13, 0}, {0, 100, 0}, {1, 365, 1}, {0, 100, 0}, {1, 232, 1}, {1, 21, 0},
      {1, 191, 1}, {0, 100, 0}, {1, 328, 1}, {1, 7, 0}, {0, 100, 0}, {1, 175, 1}, {1, 100, 0}, {1, 32, 1}, {1, 100, 0}, {1, 107, 1}, {0, 100, 0}, {1, 212, 1},
      {1, 72, 0}, {1, 295, 1}, {1, 100, 0}, {1, 4, 1}, {1, 100, 0}, {1, 5, 1}, {0, 41, 0}, {1, 403, 1}, {1, 100, 0}, {1, 283, 1}, {1, 51, 0}, {1, 351, 1},
      {0, 100, 0}, {1, 1024, 1}, {1, 100, 0}, {1, 43, 1}, {1, 84, 0}, {0, 22, 0}, {0, 100, 0}, {1, 5, 1}, {1, 83, 0}, {1, 4, 0}, {1, 44, 0}, {1, 534, 1},
      {0, 48, 0}, {1, 222, 1}, {1, 91, 0}, {1, 215, 1}, {1, 18, 0}, {1, 55, 1}, {1, 18, 0}, {1, 100, 0}, {1, 279, 1}, {0, 100, 0}, {1, 382, 1}, {0, 11, 0},
      {1, 535, 1}, {0, 100, 0}, {1, 226, 1}, {0, 100, 0}, {1, 27, 1}, {0, 100, 0}, {1, 291, 1}, {0, 96, 0}, {1, 139, 1}, {0, 69, 0}, {1, 122, 1}, {1, 89, 0},
      {1, 27, 0}, {1, 211, 1}, {0, 85, 0}, {1, 123, 1}, {0, 15, 0}, {1, 280, 1}, {0, 1, 0}, {1, 237, 1}, {0, 73, 0}, {0, 70, 0}, {1, 479, 1}, {1, 100, 0},
      {1, 42, 1}, {1, 65, 0}, {0, 11, 0}, {1, 143, 1}, {0, 34, 0}, {1, 201, 1}, {1, 60, 0}, {1, 922, 1}, {1, 100, 0}, {1, 363, 1}, {1, 80, 0}, {1, 100, 0},
      {1, 499, 1}, {0, 100, 0}, {1, 271, 1}, {0, 62, 0}, {1, 651, 1}, {1, 100, 0}, {1, 581, 1}, {1, 50, 0}, {0, 98, 0}, {1, 536, 1}, {1, 100, 0}, {1, 220, 1},
      {0, 51, 0}, {1, 120, 1}, {1, 100, 0}, {1, 51, 1}, {0, 100, 0}, {1, 208, 1}, {0, 100, 0}, {1, 13, 1}, {1, 8, 0}, {0, 100, 0}, {1, 141, 1}, {0, 100, 0},
      {1, 556, 1}, {1, 25, 0}, {1, 248, 1}, {0, 20, 0}, {1, 346, 1}, {0, 100, 0}, {1, 208, 1}, {0, 100, 0}, {1, 394, 1}, {0, 100, 0}, {1, 254, 1}, {1, 100, 0},
      {1, 260, 1}, {0, 89, 0}, {0, 84, 0}, {1, 861, 1}, {1, 100, 0}, {1, 138, 1}, {1, 100, 0}, {1, 25, 1}, {0, 85, 0}, {1, 1226, 1}, {0, 5, 0}, {1, 816, 1},
      {1, 100, 0}, {1, 224, 1}, {1, 50, 0}, {1, 226, 1}, {0, 100, 0}, {1, 148, 1}, {1, 100, 0}, {1, 100, 1}, {1, 100, 0}, {1, 133, 1}, {0, 100, 0}, {1, 471, 1},
      {1, 100, 0}, {1, 636, 1}, {1, 100, 0}, {1, 48, 1}, {1, 31, 0}, {1, 254, 1}, {0, 11, 0}, {1, 187, 1}, {0, 100, 0}, {1, 7, 1}, {0, 42, 0}, {1, 847, 1},
      {0, 100, 0}, {1, 16, 1}, {1, 100, 0}, {1, 305, 1}, {1, 100, 0}, {1, 888, 1}, {1, 84, 0}, {1, 947, 1}, {1, 8, 0}, {1, 19, 0}, {1, 907, 1}, {0, 100, 0},
      {1, 449, 1}, {1, 38, 0}, {1, 64, 0}, {1, 1125, 1}, {1, 8, 0}, {0, 100, 0}, {1, 895, 1}, {0, 100, 0}, {1, 137, 1}, {1, 100, 0}, {1, 186, 1}, {0, 100, 0},
      {1, 402, 1}, {1, 59, 0}, {1, 5, 0}, {1, 80, 1}, {1, 82, 0}, {1, 480, 1}, {1, 26, 0}, {1, 94, 1}, {0, 100, 0}, {1, 89, 1}, {0, 100, 0}, {1, 387, 1},
      {1, 100, 0}, {1, 271, 1}, {1, 26, 0}, {0, 36, 0}, {1, 833, 1}, {1, 73, 0}, {1, 397, 1}, {1, 100, 0}, {1, 509, 1}, {0, 100, 0}, {1, 183, 1}, {0, 17, 0},
      {1, 126, 1}, {1, 30, 0}, {1, 370, 1}, {1, 20, 0}, {1, 100, 0}, {1, 58, 1}, {0, 18, 0}, {1, 222, 1}, {1, 100, 0}, {1, 238, 1}, {1, 80, 0}, {0, 100, 0},
      {1, 97, 1}, {1, 60, 0}, {1, 386, 1}, {1, 2, 0}, {1, 100, 0}, {1, 433, 1}, {1, 100, 0}, {1, 21, 1}, {0, 42, 0}, {1, 609, 1}, {0, 100, 0}, {1, 52, 1},
      {0, 46, 0}, {1, 103, 1}, {1, 100, 0}, {1, 1566, 1}, {0, 35, 0}, {1, 220, 1}, {1, 40, 0}, {1, 553, 1}, {1, 100, 0}, {1, 39, 1}, {0, 71, 0}, {1, 75, 1},
      {1, 100, 0}, {1, 132, 1}, {0, 100, 0}, {1, 91, 1}, {1, 12, 0}, {0, 100, 0}, {1, 163, 1}, {0, 41, 0}, {1, 289, 1}, {0, 1, 0}, {1, 831, 1}, {1, 6, 0},
      {1, 358, 1}, {0, 100, 0}, {1, 109, 1}, {1, 93, 0}, {0, 85, 0}, {1, 300, 1}, {0, 100, 0}, {1, 14, 1}, {0, 26, 0}, {1, 2320, 1}, {0, 100, 0}, {1, 202, 1},
      {0, 93, 0}, {1, 141, 1}, {1, 39, 0}, {1, 246, 1}, {0, 68, 0}, {1, 381, 1}, {0, 33, 0}, {1, 733, 1}, {1, 60, 0}, {1, 191, 1}, {1, 100, 0}, {1, 240, 1},
      {1, 8, 0}, {1, 597, 1}, {1, 35, 0}, {1, 125, 1}, {1, 71, 0}, {1, 132, 1}, {1, 45, 0}, {1, 366, 1}, {1, 59, 0}, {0, 25, 0}, {1, 163, 1}, {1, 16, 0},
      {1, 273, 1}, {1, 1, 0}, {0, 100, 0}, {1, 57, 1}, {0, 77, 0}, {1, 179, 1}, {1, 100, 0}, {1, 47, 1}, {1, 60, 0}, {1, 950, 1}, {1, 22, 0}, {1, 887, 1},
      {1, 100, 0}, {1, 681, 1}, {1, 31, 0}, {1, 206, 1}, {1, 100, 0}, {1, 301, 1}, {0, 100, 0}, {1, 54, 1}, {1, 100, 0}, {1, 23, 1}, {0, 100, 0}, {1, 549, 1},
      {0, 100, 0}, {1, 100, 0}, {1, 193, 1}, {0, 100, 0}, {1, 63, 1}, {1, 59, 0}, {1, 345, 1}, {0, 100, 0}, {1, 3, 1}, {1, 86, 0}, {1, 2, 0}, {1, 279, 1},
      {1, 100, 0}, {1, 445, 1}, {0, 13, 0}, {0, 100, 0}, {1, 18, 1}, {1, 24, 0}, {1, 35, 0}, {0, 100, 0}, {1, 213, 1}, {0, 100, 0}, {1, 325, 1}, {0, 100, 0},
      {1, 2, 1}, {0, 100, 0}, {1, 842, 1}, {1, 100, 0}, {1, 46, 1}, {0, 100, 0}, {1, 221, 1}, {1, 100, 0}, {1, 74, 1}, {1, 25, 0}, {1, 211, 1}, {0, 29, 0},
      {0, 100, 0}, {1, 13, 1}, {0, 100, 0}, {1, 90, 1}, {1, 10, 0}, {0, 19, 0}, {0, 13, 0}, {1, 132, 1}, {0, 100, 0}, {1, 185, 1}, {1, 32, 0}, {1, 176, 1},
      {0, 100, 0}, {1, 455, 1}, {1, 6, 0}, {0, 11, 0}, {1, 399, 1}, {1, 13, 0}, {1, 315, 1}, {1, 44, 0}, {1, 100, 0}, {1, 425, 1}, {1, 90, 0}, {1, 30, 0},
      {0, 3, 0}, {1, 116, 1}, {1, 67, 0}, {1, 306, 1}, {1, 100, 0}, {1, 53, 1}, {0, 100, 0}, {1, 1183, 1}, {0, 23, 0}, {1, 259, 1}, {0, 100, 0}, {1, 159, 1},
      {0, 27, 0}, {1, 451, 1}, {1, 24, 0}, {1, 87, 0}, {0, 100, 0}, {1, 109, 1}, {1, 100, 0}, {1, 42, 1}, {0, 100, 0}, {1, 78, 1}, {0, 32, 0}
  };

  @Test
  public void bucketValueIsComputedInDoublePrecision() {
    double bucket = computeBucketValue(true, SEED, LDContext.create(CONTEXT_KEY), null, "flagkey", null, "salt");
    // The single-precision computation this replaced produced 0.98308945 (off by ~4.4e-9), so
    // the tolerance here is chosen to fail for anything less precise than a double.
    assertEquals(0.9830894514485481, bucket, 1e-10);
  }

  @Test
  public void rolloutBoundariesAreComputedFromIntegerWeightSums() {
    List<WeightedVariation> variations = new ArrayList<>();
    for (int[] row: WEIGHT_DATA) {
      variations.add(new WeightedVariation(row[0], row[1], row[2] != 0));
    }
    Rollout rollout = new Rollout(ContextKind.DEFAULT, variations, null, RolloutKind.experiment, SEED);
    FeatureFlag flag = ModelBuilders.flagBuilder("flagkey")
        .on(true)
        .variations(true, false)
        .fallthrough(rollout)
        .salt("salt")
        .build();

    EvalResult result = BASE_EVALUATOR.evaluate(flag, LDContext.create(CONTEXT_KEY), expectNoPrerequisiteEvals());

    // The context's bucket value is 0.98308945..., and the cumulative weight through the 536th
    // weighted variation (variation 1, untracked) is 98309, so the context belongs in that
    // bucket and is not in the experiment. Accumulating a floating-point sum of the weights
    // instead drifts that boundary below the bucket value, wrongly placing the context in the
    // next bucket (variation 0, tracked) and reporting it as in the experiment.
    assertThat(result.getVariationIndex(), equalTo(1));
    assertThat(result.getReason().getKind(), equalTo(EvaluationReason.Kind.FALLTHROUGH));
    assertThat(result.getReason().isInExperiment(), equalTo(false));
  }
}
