package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.EvaluationReason.ErrorKind;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.LDValueType;
import com.launchdarkly.sdk.server.DataModel.FeatureFlag;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.DataKind;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.ItemDescriptor;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes.KeyedItems;

import org.junit.Test;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static com.launchdarkly.sdk.server.DataModel.FEATURES;
import static com.launchdarkly.sdk.server.EvaluatorTestUtil.BASE_USER;
import static com.launchdarkly.sdk.server.ModelBuilders.flagBuilder;
import static com.launchdarkly.sdk.server.TestComponents.nullLogger;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("javadoc")
public class InputValidatingEvaluatorOverrideTest {
  private static ReadOnlyStore storeWith(FeatureFlag... flags) {
    Map<String, ItemDescriptor> items = new HashMap<>();
    for (FeatureFlag f : flags) {
      items.put(f.getKey(), new ItemDescriptor(f.getVersion(), f));
    }
    return new ReadOnlyStore() {
      @Override
      public ItemDescriptor get(DataKind kind, String key) {
        return kind == FEATURES ? items.get(key) : null;
      }

      @Override
      public KeyedItems<ItemDescriptor> getAll(DataKind kind) {
        return kind == FEATURES ? new KeyedItems<>(items.entrySet()) : new KeyedItems<>(Collections.emptyList());
      }

      @Override
      public boolean isInitialized() {
        return true;
      }
    };
  }

  private static InputValidatingEvaluator evaluatorOver(ReadOnlyStore store) {
    return new InputValidatingEvaluator(store, null, null, new NoOpEventProcessor(), nullLogger);
  }

  @Test
  public void typeMismatchOnOverrideFlagKeepsMarking() {
    FeatureFlag flag = flagBuilder("flag").on(false).offVariation(0).variations(LDValue.of("a string"))
        .build().markedAsOverride();
    EvalResultAndFlag result = evaluatorOver(storeWith(flag)).evaluate("flag", BASE_USER, LDValue.of(true),
        LDValueType.BOOLEAN, InputValidatingEvaluator.NO_OP_EVALUATION_EVENT_RECORDER);

    assertEquals(LDValue.of(true), result.getResult().getValue());
    assertEquals(EvaluationReason.error(ErrorKind.WRONG_TYPE).withOverrideAffected(true), result.getResult().getReason());
    assertTrue(result.getResult().isOverrideAffected());
  }

  @Test
  public void typeMismatchOnOrdinaryFlagIsNotMarked() {
    FeatureFlag flag = flagBuilder("flag").on(false).offVariation(0).variations(LDValue.of("a string")).build();
    EvalResultAndFlag result = evaluatorOver(storeWith(flag)).evaluate("flag", BASE_USER, LDValue.of(true),
        LDValueType.BOOLEAN, InputValidatingEvaluator.NO_OP_EVALUATION_EVENT_RECORDER);

    assertEquals(EvaluationReason.error(ErrorKind.WRONG_TYPE), result.getResult().getReason());
    assertFalse(result.getResult().isOverrideAffected());
  }

  @Test
  public void errorResultOfOverrideFlagKeepsMarkingWithCallerDefault() {
    FeatureFlag malformed = flagBuilder("flag").on(true).variations(LDValue.of("only"))
        .fallthroughVariation(5).offVariation(0).build().markedAsOverride();
    EvalResultAndFlag result = evaluatorOver(storeWith(malformed)).evaluate("flag", BASE_USER, LDValue.of("fallback"),
        null, InputValidatingEvaluator.NO_OP_EVALUATION_EVENT_RECORDER);

    assertEquals(LDValue.of("fallback"), result.getResult().getValue());
    assertTrue(result.getResult().isNoVariation());
    assertEquals(EvaluationReason.error(ErrorKind.MALFORMED_FLAG).withOverrideAffected(true), result.getResult().getReason());
  }

  @Test
  public void allFlagsStateKeepsMarkedReason() {
    FeatureFlag marked = flagBuilder("marked").on(false).offVariation(0).variations(LDValue.of("x")).build()
        .markedAsOverride();
    FeatureFlag plain = flagBuilder("plain").on(false).offVariation(0).variations(LDValue.of("y")).build();
    FeatureFlagsState state = evaluatorOver(storeWith(marked, plain)).allFlagsState(BASE_USER, FlagsStateOption.WITH_REASONS);

    assertTrue(state.isValid());
    assertTrue(state.getFlagReason("marked").isOverrideAffected());
    assertFalse(state.getFlagReason("plain").isOverrideAffected());
    Map.Entry<String, LDValue> expected = new AbstractMap.SimpleEntry<>("marked", LDValue.of("x"));
    assertEquals(expected.getValue(), state.getFlagValue("marked"));
  }
}
