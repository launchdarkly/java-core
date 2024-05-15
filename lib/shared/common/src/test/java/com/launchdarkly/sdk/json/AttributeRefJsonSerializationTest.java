package com.launchdarkly.sdk.json;

import com.launchdarkly.sdk.AttributeRef;
import com.launchdarkly.sdk.BaseTest;

import org.junit.Test;

import static com.launchdarkly.sdk.json.JsonTestHelpers.verifyDeserialize;
import static com.launchdarkly.sdk.json.JsonTestHelpers.verifyDeserializeInvalidJson;
import static com.launchdarkly.sdk.json.JsonTestHelpers.verifySerialize;

@SuppressWarnings("javadoc")
public class AttributeRefJsonSerializationTest extends BaseTest {
  @Test
  public void serialization() throws Exception {
    testSerialization("a", "\"a\"");
    testSerialization("/a/b", "\"/a/b\"");
    testSerialization("///invalid", "\"///invalid\"");
  }
  
  private void testSerialization(String attrPath, String expected) throws Exception {
    verifySerialize(AttributeRef.fromPath(attrPath), expected);
  }
  
  @Test
  public void deserialization() throws Exception {
    testDeserialization("\"a\"", "a");
    testDeserialization("\"/a/b\"", "/a/b");
    testDeserialization("\"///invalid\"", "///invalid");
    
    verifyDeserializeInvalidJson(AttributeRef.class, "2");
    verifyDeserializeInvalidJson(AttributeRef.class, "[]");
    verifyDeserializeInvalidJson(AttributeRef.class, "{}");
  }
  
  private void testDeserialization(String json, String attrPath) throws Exception {
    verifyDeserialize(AttributeRef.fromPath(attrPath), json);
  }
}
