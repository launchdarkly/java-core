package com.launchdarkly.sdk.json;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.launchdarkly.sdk.EvaluationReason;
import com.launchdarkly.sdk.LDValue;

import org.junit.Test;

import java.io.StringWriter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@SuppressWarnings("javadoc")
public class LDJacksonTest {
  @Test
  public void testInternalReaderAdapter() throws Exception {
    // This and testInternalWriterAdapter verify that all of our reader/writer delegation
    // methods work as expected, regardless of whether or not they are exercised indirectly
    // by our other unit tests.
    String json = "[null,false,true,1.5,2,3,\"x\",{\"a\":false}]";
    JsonParser p = new JsonFactory().createParser(json);
    p.nextToken();
    try (JsonReader jr = new LDJackson.GsonReaderToJacksonParserAdapter(p)) {
      jr.beginArray();
      assertEquals(true, jr.hasNext());
      jr.nextNull();
      assertEquals(JsonToken.BOOLEAN, jr.peek());
      jr.skipValue();
      assertEquals(true, jr.nextBoolean());
      assertEquals(1.5d, jr.nextDouble(), 0);
      assertEquals(2, jr.nextInt());
      assertEquals(3, jr.nextLong());
      assertEquals("x", jr.nextString());
      jr.beginObject();
      assertEquals(true, jr.hasNext());
      assertEquals("a", jr.nextName());
      assertEquals(false, jr.nextBoolean());
      assertEquals(false, jr.hasNext());
      jr.endObject();
      assertEquals(false, jr.hasNext());
      jr.endArray();
      assertEquals(JsonToken.END_DOCUMENT, jr.peek());
    }
  }
  
  @Test
  public void testInternalWriterAdapter() throws Exception {
    try (StringWriter sw = new StringWriter()) {
      JsonGenerator gen = new JsonFactory().createGenerator(sw);
      try (JsonWriter jw = new LDJackson.GsonWriterToJacksonGeneratorAdapter(gen)) {
        jw.beginArray();
        jw.nullValue();
        jw.value(true);
        jw.value(Boolean.valueOf(true));
        jw.value((Boolean)null);
        jw.value((double)1);
        jw.value((double)1.5);
        jw.value((long)2);
        jw.value(Float.valueOf(3));
        jw.value(Float.valueOf(3.5f));
        jw.value((Float)null);
        jw.value("x");
        jw.beginObject();
        jw.name("a");
        jw.value(false);
        jw.endObject();
        jw.jsonValue("123");
        jw.endArray();
        jw.flush();
      }
      gen.flush();
      String expected = "[null,true,true,null,1,1.5,2,3,3.5,null,\"x\",{\"a\":false},123]";
      assertEquals(expected, sw.toString().replace(" ", ""));
    }
  }

  @Test(expected=JsonParseException.class)
  public void parseExceptionIsThrownForMalformedJson() throws Exception {
    JsonTestHelpers.configureJacksonMapper().readValue("[1:,2]", LDValue.class);
  }
  
  @Test
  public void parseExceptionIsThrownForIllegalValue() throws Exception {
    try {
      JsonTestHelpers.configureJacksonMapper().readValue("{\"kind\":\"NOTGOOD\"}", EvaluationReason.class);
      fail("expected exception");
    } catch (JsonParseException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("unsupported value \"NOTGOOD\""));
    }
  }
}
