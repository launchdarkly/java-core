package com.launchdarkly.sdk.json;

import com.launchdarkly.sdk.BaseTest;
import com.launchdarkly.sdk.UserAttribute;

import org.junit.Test;

import static com.launchdarkly.sdk.json.JsonTestHelpers.verifyDeserializeInvalidJson;
import static com.launchdarkly.sdk.json.JsonTestHelpers.verifySerializeAndDeserialize;

@SuppressWarnings("javadoc")
public class UserAttributeJsonSerializationTest extends BaseTest {
  @Test
  public void userAttributeJsonSerializations() throws Exception {
    verifySerializeAndDeserialize(UserAttribute.NAME, "\"name\"");
    verifySerializeAndDeserialize(UserAttribute.forName("custom-attr"), "\"custom-attr\"");

    verifyDeserializeInvalidJson(UserAttribute.class, "3");
  }
}
