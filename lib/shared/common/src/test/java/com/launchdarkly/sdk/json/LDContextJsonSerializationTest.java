package com.launchdarkly.sdk.json;

import com.google.gson.JsonIOException;
import com.launchdarkly.sdk.ContextKind;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;

import org.junit.Test;

import static com.launchdarkly.sdk.json.JsonTestHelpers.verifyDeserialize;
import static com.launchdarkly.sdk.json.JsonTestHelpers.verifySerialize;
import static com.launchdarkly.sdk.json.JsonTestHelpers.verifySerializeAndDeserialize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@SuppressWarnings("javadoc")
public class LDContextJsonSerializationTest {
  private static final ContextKind
    kind1 = ContextKind.of("kind1"),
    kind2 = ContextKind.of("kind2");

  private static final LDValue[] ALL_TYPE_VALUES = new LDValue[] {
      LDValue.of(true), LDValue.of(1), LDValue.of(1.5), LDValue.of("c"),
      LDValue.arrayOf(), LDValue.buildObject().build()
  };
  
  @Test
  public void minimalJsonEncoding() throws Exception {
    LDContext context = LDContext.create("userkey");
    verifySerializeAndDeserialize(context, "{\"kind\":\"user\",\"key\":\"userkey\"}");

    verifySerialize((LDContext)null, "null");
  }
  
  @Test
  public void singleKindContexts() throws Exception {
    verifySerializeAndDeserialize(
        LDContext.create("a"),
        "{\"kind\":\"user\",\"key\":\"a\"}");

    verifySerializeAndDeserialize(
        LDContext.create(kind1, "a"),
        "{\"kind\":\"kind1\",\"key\":\"a\"}");

    verifySerializeAndDeserialize(
        LDContext.builder("a").name("b").build(),
        "{\"kind\":\"user\",\"key\":\"a\",\"name\":\"b\"}");

    verifySerializeAndDeserialize(
        LDContext.builder("a").anonymous(true).build(),
        "{\"kind\":\"user\",\"key\":\"a\",\"anonymous\":true}");

    for (LDValue customValue: ALL_TYPE_VALUES) {
      verifySerializeAndDeserialize(
          LDContext.builder("a").set("b", customValue).build(),
          "{\"kind\":\"user\",\"key\":\"a\",\"b\":" + customValue.toJsonString() + "}");
    }

    verifySerializeAndDeserialize(
        LDContext.builder("a").privateAttributes("b").build(),
        "{\"kind\":\"user\",\"key\":\"a\",\"_meta\":{\"privateAttributes\":[\"b\"]}}");

    verifySerializeAndDeserialize(
        LDContext.builder("a").privateAttributes("/b/c").build(),
        "{\"kind\":\"user\",\"key\":\"a\",\"_meta\":{\"privateAttributes\":[\"/b/c\"]}}");
  }
  
  @Test
  public void multiKindContext() throws Exception {
    verifySerializeAndDeserialize(
        LDContext.createMulti(LDContext.create(kind1, "a"), LDContext.create(kind2, "b")),
        "{\"kind\":\"multi\",\"kind1\":{\"key\":\"a\"},\"kind2\":{\"key\":\"b\"}}");
  }
  
  @Test
  public void convertOldUser() throws Exception {
    verifyDeserialize(LDContext.create("a"), "{\"key\":\"a\"}");
    
    verifyDeserialize(LDContext.builder("a").name("b").build(),
        "{\"key\":\"a\",\"name\":\"b\"}");
    
    verifyDeserialize(LDContext.builder("a").anonymous(true).build(),
        "{\"key\":\"a\",\"anonymous\":true}");

    verifyDeserialize(LDContext.builder("a").build(),
        "{\"key\":\"a\",\"anonymous\":false}");

    verifyDeserialize(LDContext.builder("a").build(),
        "{\"key\":\"a\",\"anonymous\":null}");

    for (String builtInName: new String[] { "firstName", "lastName", "email", "country", "ip", "avatar" }) {
      verifyDeserialize(LDContext.builder("a").set(builtInName, "b").build(),
          "{\"key\":\"a\",\"" + builtInName + "\":\"b\"}");
    }
    
    for (LDValue customValue: ALL_TYPE_VALUES) {
      verifyDeserialize(
          LDContext.builder("a").set("b", customValue).build(),
          "{\"key\":\"a\",\"custom\":{\"b\":" + customValue.toJsonString() + "}}");
    }

    verifyDeserialize(LDContext.builder("a").privateAttributes("b").build(),
        "{\"key\":\"a\",\"privateAttributeNames\":[\"b\"]}");
    
    // For old user JSON only, an empty key is allowed; an LDContext can't be constructed in this state.
    LDContext contextWithEmptyKey = JsonSerialization.deserialize("{\"key\":\"\"}", LDContext.class);
    assertTrue(contextWithEmptyKey.isValid());
    assertEquals("", contextWithEmptyKey.getKey());
  }
  
  @Test(expected=JsonIOException.class)
  public void serializeInvalidContext() throws Exception {
    JsonSerialization.serialize(LDContext.create(""));
  }
  
  @Test
  public void deserializeContextWithValidationError() throws Exception {
    for (String json: new String[] {
        "{\"kind\":\"\",\"key\":\"a\"}",
        "{\"kind\":\"a\",\"key\":\"\"}",
        "{\"kind\":\"kind\",\"key\":\"a\"}",
        "{\"kind\":\"ørg\",\"key\":\"a\"}",
        "{\"kind\":\"a\",\"key\":\"b\",\"name\":3}",
        "{\"kind\":\"a\",\"key\":\"b\",\"anonymous\":\"x\"}",
        "{\"kind\":\"a\",\"key\":\"b\",\"_meta\":\"x\"}",
        
        // invalid old-style user JSON
        "{\"key\":null}",
        "{\"key\":\"a\",\"name\":3}",
        "{\"key\":\"a\",\"anonymous\":\"x\"}",
        "{\"key\":\"a\",\"custom\":\"x\"}",
        "{\"key\":\"a\",\"privateAttributeNames\":3}",
        
        "{}",
        ""
    }) {
      try {
        JsonSerialization.deserialize(json, LDContext.class);
        fail("expected deserialization to fail, but it passed, for JSON: " + json);
      } catch (SerializationException e) {}
    }
  }
  
  @Test(expected=SerializationException.class)
  public void deserializeContextWithTypeError() throws Exception {
    JsonSerialization.deserialize("{\"kind\":\"a\",\"key\":3}", LDContext.class);
  }
}
