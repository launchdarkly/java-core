package com.launchdarkly.sdk.json;

import static com.launchdarkly.sdk.EvaluationReason.BigSegmentsStatus.HEALTHY;
import static com.launchdarkly.sdk.EvaluationReason.BigSegmentsStatus.STORE_ERROR;
import static com.launchdarkly.sdk.json.JsonTestHelpers.verifyDeserialize;
import static com.launchdarkly.sdk.json.JsonTestHelpers.verifyDeserializeInvalidJson;
import static com.launchdarkly.sdk.json.JsonTestHelpers.verifySerialize;
import static com.launchdarkly.sdk.json.JsonTestHelpers.verifySerializeAndDeserialize;

import com.launchdarkly.sdk.BaseTest;
import com.launchdarkly.sdk.EvaluationReason;

import org.junit.Test;

@SuppressWarnings("javadoc")
public class EvaluationReasonJsonSerializationTest extends BaseTest {
  @Test
  public void reasonJsonSerializations() throws Exception {
    verifySerializeAndDeserialize(EvaluationReason.off(), "{\"kind\":\"OFF\"}");
    verifySerializeAndDeserialize(EvaluationReason.fallthrough(), "{\"kind\":\"FALLTHROUGH\"}");
    verifySerializeAndDeserialize(EvaluationReason.fallthrough(false), "{\"kind\":\"FALLTHROUGH\"}");
    verifySerializeAndDeserialize(EvaluationReason.fallthrough(true), "{\"kind\":\"FALLTHROUGH\",\"inExperiment\":true}");
    verifySerializeAndDeserialize(EvaluationReason.fallthrough().withBigSegmentsStatus(HEALTHY),
        "{\"kind\":\"FALLTHROUGH\",\"bigSegmentsStatus\":\"HEALTHY\"}");
    verifySerializeAndDeserialize(EvaluationReason.targetMatch(), "{\"kind\":\"TARGET_MATCH\"}");
    verifySerializeAndDeserialize(EvaluationReason.ruleMatch(1, "id"),
        "{\"kind\":\"RULE_MATCH\",\"ruleIndex\":1,\"ruleId\":\"id\"}");
    verifySerializeAndDeserialize(EvaluationReason.ruleMatch(1, "id", false),
        "{\"kind\":\"RULE_MATCH\",\"ruleIndex\":1,\"ruleId\":\"id\"}");
    verifySerializeAndDeserialize(EvaluationReason.ruleMatch(1, "id", true),
        "{\"kind\":\"RULE_MATCH\",\"ruleIndex\":1,\"ruleId\":\"id\",\"inExperiment\":true}");
    verifySerializeAndDeserialize(EvaluationReason.ruleMatch(1, null).withBigSegmentsStatus(STORE_ERROR),
        "{\"kind\":\"RULE_MATCH\",\"ruleIndex\":1,\"bigSegmentsStatus\":\"STORE_ERROR\"}");
    verifySerializeAndDeserialize(EvaluationReason.ruleMatch(1, null),
        "{\"kind\":\"RULE_MATCH\",\"ruleIndex\":1}");
    verifySerializeAndDeserialize(EvaluationReason.ruleMatch(1, null, false),
        "{\"kind\":\"RULE_MATCH\",\"ruleIndex\":1}");
    verifySerializeAndDeserialize(EvaluationReason.ruleMatch(1, null, true),
        "{\"kind\":\"RULE_MATCH\",\"ruleIndex\":1,\"inExperiment\":true}");
    verifySerializeAndDeserialize(EvaluationReason.prerequisiteFailed("key"),
        "{\"kind\":\"PREREQUISITE_FAILED\",\"prerequisiteKey\":\"key\"}");
    verifySerializeAndDeserialize(EvaluationReason.error(EvaluationReason.ErrorKind.FLAG_NOT_FOUND),
        "{\"kind\":\"ERROR\",\"errorKind\":\"FLAG_NOT_FOUND\"}");

    // properties with defaults can be included, explicit default values are ignored in parsing
    verifyDeserialize(EvaluationReason.fallthrough(false), "{\"kind\":\"FALLTHROUGH\",\"inExperiment\":false}");
    verifyDeserialize(EvaluationReason.ruleMatch(1, "id", false),
        "{\"kind\":\"RULE_MATCH\",\"ruleIndex\":1,\"ruleId\":\"id\",\"inExperiment\":false}");
    verifyDeserialize(EvaluationReason.ruleMatch(1, null, false),
        "{\"kind\":\"RULE_MATCH\",\"ruleIndex\":1,\"ruleId\":null}");

    // unknown properties are ignored
    JsonTestHelpers.verifyDeserialize(EvaluationReason.off(), "{\"kind\":\"OFF\",\"other\":true}");
    
    verifyDeserializeInvalidJson(EvaluationReason.class, "3");
    verifyDeserializeInvalidJson(EvaluationReason.class, "{}"); // must have "kind"
    verifyDeserializeInvalidJson(EvaluationReason.class, "{\"kind\":3}");
    verifyDeserializeInvalidJson(EvaluationReason.class, "{\"kind\":\"other\"}");
    verifyDeserializeInvalidJson(EvaluationReason.class, "{\"kind\":\"RULE_MATCH\",\"ruleIndex\":1,\"ruleId\":3}");
  }

  @Test
  public void overrideAffectedIsSerializedOnlyWhenTrue() throws Exception {
    verifySerializeAndDeserialize(EvaluationReason.off().withOverrideAffected(true),
        "{\"kind\":\"OFF\",\"overrideAffected\":true}");
    verifySerializeAndDeserialize(EvaluationReason.fallthrough(true).withOverrideAffected(true),
        "{\"kind\":\"FALLTHROUGH\",\"inExperiment\":true,\"overrideAffected\":true}");
    verifySerializeAndDeserialize(EvaluationReason.ruleMatch(1, "id").withOverrideAffected(true),
        "{\"kind\":\"RULE_MATCH\",\"ruleIndex\":1,\"ruleId\":\"id\",\"overrideAffected\":true}");
    verifySerializeAndDeserialize(EvaluationReason.prerequisiteFailed("key").withOverrideAffected(true),
        "{\"kind\":\"PREREQUISITE_FAILED\",\"prerequisiteKey\":\"key\",\"overrideAffected\":true}");
    verifySerializeAndDeserialize(EvaluationReason.error(EvaluationReason.ErrorKind.MALFORMED_FLAG).withOverrideAffected(true),
        "{\"kind\":\"ERROR\",\"errorKind\":\"MALFORMED_FLAG\",\"overrideAffected\":true}");
    verifySerializeAndDeserialize(EvaluationReason.fallthrough().withBigSegmentsStatus(HEALTHY).withOverrideAffected(true),
        "{\"kind\":\"FALLTHROUGH\",\"bigSegmentsStatus\":\"HEALTHY\",\"overrideAffected\":true}");

    // A false value is never written, and an explicit false parses the same as an absent property.
    verifySerialize(EvaluationReason.off().withOverrideAffected(false), "{\"kind\":\"OFF\"}");
    verifyDeserialize(EvaluationReason.off(), "{\"kind\":\"OFF\",\"overrideAffected\":false}");
  }

  @Test
  public void errorSerializationWithException() throws Exception {
    // We do *not* want the JSON representation to include the exception, because that is used in events, and
    // the LD event service won't know what to do with that field (which will also contain a big stacktrace).
    EvaluationReason reason = EvaluationReason.exception(new Exception("something happened"));
    String expectedJsonString = "{\"kind\":\"ERROR\",\"errorKind\":\"EXCEPTION\"}";
    verifySerialize(reason, expectedJsonString);
  }
}
