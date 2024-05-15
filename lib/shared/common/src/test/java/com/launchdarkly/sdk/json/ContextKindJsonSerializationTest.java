package com.launchdarkly.sdk.json;

import com.launchdarkly.sdk.BaseTest;
import com.launchdarkly.sdk.ContextKind;

import org.junit.Test;

import static com.launchdarkly.sdk.json.JsonTestHelpers.verifySerializeAndDeserialize;
import static com.launchdarkly.sdk.json.JsonTestHelpers.verifyDeserializeInvalidJson;

@SuppressWarnings("javadoc")
public class ContextKindJsonSerializationTest extends BaseTest {
  @Test
  public void serializationAndDeserialization() throws Exception {
    verifySerializeAndDeserialize(ContextKind.DEFAULT, "\"user\"");
    verifySerializeAndDeserialize(ContextKind.of("org"), "\"org\"");
  }
  
  @Test
  public void deserializeInvalid() throws Exception {
    verifyDeserializeInvalidJson(ContextKind.class, "true");
    verifyDeserializeInvalidJson(ContextKind.class, "3");
    verifyDeserializeInvalidJson(ContextKind.class, "{}");
    verifyDeserializeInvalidJson(ContextKind.class, "[]");
  }
}
