package com.launchdarkly.sdk.json;

import com.launchdarkly.sdk.BaseTest;
import com.launchdarkly.sdk.LDUser;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.UserAttribute;

import org.junit.Test;

import static com.launchdarkly.sdk.TestHelpers.builtInAttributes;
import static com.launchdarkly.sdk.json.JsonTestHelpers.verifyDeserialize;
import static com.launchdarkly.sdk.json.JsonTestHelpers.verifyDeserializeInvalidJson;
import static com.launchdarkly.sdk.json.JsonTestHelpers.verifySerialize;
import static com.launchdarkly.sdk.json.JsonTestHelpers.verifySerializeAndDeserialize;

@SuppressWarnings("javadoc")
public class LDUserJsonSerializationTest extends BaseTest {
  @Test
  public void minimalJsonEncoding() throws Exception {
    LDUser user = new LDUser("userkey");
    verifySerializeAndDeserialize(user, "{\"key\":\"userkey\"}");
    
    verifyDeserializeInvalidJson(LDUser.class, "3");
    verifyDeserializeInvalidJson(LDUser.class, "{\"key\":\"userkey\",\"name\":3");
    
    verifySerialize((LDUser)null, "null");
  }

  @Test
  public void defaultJsonEncodingWithoutPrivateAttributes() throws Exception {
    LDUser user = new LDUser.Builder("userkey")
        .ip("i")
        .email("e")
        .name("n")
        .avatar("a")
        .firstName("f")
        .lastName("l")
        .country("c")
        .anonymous(true)
        .custom("c1", "v1")
        .custom("c2", "v2")
        .build();
    LDValue expectedJson = LDValue.buildObject()
        .put("key", "userkey")
        .put("ip", "i")
        .put("email", "e")
        .put("name", "n")
        .put("avatar", "a")
        .put("firstName", "f")
        .put("lastName", "l")
        .put("country", "c")
        .put("anonymous", true)
        .put("custom", LDValue.buildObject().put("c1", "v1").put("c2", "v2").build())
        .build();
    verifySerializeAndDeserialize(user, expectedJson.toJsonString());
  }

  @Test
  public void defaultJsonEncodingWithPrivateAttributes() throws Exception {
    LDUser user = new LDUser.Builder("userkey")
        .email("e")
        .privateName("n")
        .privateCountry("c")
        .build();
    LDValue expectedJson = LDValue.buildObject()
        .put("key", "userkey")
        .put("email", "e")
        .put("name", "n")
        .put("country", "c")
        .put("privateAttributeNames", LDValue.buildArray().add("name").add("country").build())
        .build();
    verifySerializeAndDeserialize(user, expectedJson.toJsonString());
  }
  
  @Test
  public void explicitNullsAreIgnored() throws Exception {
    LDUser user = new LDUser("userkey");
    StringBuilder sb = new StringBuilder().append("{\"key\":\"userkey\"");
    for (UserAttribute a: builtInAttributes()) {
      if (a != UserAttribute.KEY) {
        sb.append(",\"").append(a.getName()).append("\":null");
      }
    }
    sb.append(",\"custom\":null,\"privateAttributeNames\":null}");
    verifyDeserialize(user, sb.toString());
  }
  
  @Test
  public void unknownKeysAreIgnored() throws Exception {
    LDUser user = new LDUser.Builder("userkey").name("x").build();
    verifyDeserialize(user, "{\"key\":\"userkey\",\"other\":true,\"name\":\"x\"}");
  }
}
