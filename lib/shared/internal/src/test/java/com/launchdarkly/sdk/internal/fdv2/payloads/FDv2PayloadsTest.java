package com.launchdarkly.sdk.internal.fdv2;

import com.google.gson.JsonElement;
import com.google.gson.stream.JsonReader;
import com.launchdarkly.sdk.internal.BaseInternalTest;
import com.launchdarkly.sdk.internal.fdv2.payloads.DeleteObject;
import com.launchdarkly.sdk.internal.fdv2.payloads.Error;
import com.launchdarkly.sdk.internal.fdv2.payloads.FDv2Event;
import com.launchdarkly.sdk.internal.fdv2.payloads.Goodbye;
import com.launchdarkly.sdk.internal.fdv2.payloads.IntentCode;
import com.launchdarkly.sdk.internal.fdv2.payloads.PayloadTransferred;
import com.launchdarkly.sdk.internal.fdv2.payloads.PutObject;
import com.launchdarkly.sdk.internal.fdv2.payloads.ServerIntent;
import com.launchdarkly.sdk.json.SerializationException;

import org.junit.Test;

import java.io.StringReader;
import java.util.List;

import static com.launchdarkly.sdk.internal.GsonHelpers.gsonInstance;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("javadoc")
public class FDv2PayloadsTest extends BaseInternalTest {

  @Test
  public void serverIntent_CanDeserializeAndReserialize() throws Exception {
    String json = "{\n" +
        "  \"payloads\": [\n" +
        "    {\n" +
        "      \"id\": \"payload-123\",\n" +
        "      \"target\": 42,\n" +
        "      \"intentCode\": \"xfer-full\",\n" +
        "      \"reason\": \"payload-missing\"\n" +
        "    }\n" +
        "  ]\n" +
        "}";

    ServerIntent serverIntent = ServerIntent.parse(new JsonReader(new StringReader(json)));

    assertNotNull(serverIntent);
    assertEquals(1, serverIntent.getPayloads().size());
    assertEquals("payload-123", serverIntent.getPayloads().get(0).getId());
    assertEquals(42, serverIntent.getPayloads().get(0).getTarget());
    assertEquals(IntentCode.TRANSFER_FULL, serverIntent.getPayloads().get(0).getIntentCode());
    assertEquals("payload-missing", serverIntent.getPayloads().get(0).getReason());

    // Reserialize and verify
    String reserialized = gsonInstance().toJson(serverIntent);
    ServerIntent deserialized2 = ServerIntent.parse(new JsonReader(new StringReader(reserialized)));
    assertEquals("payload-123", deserialized2.getPayloads().get(0).getId());
    assertEquals(42, deserialized2.getPayloads().get(0).getTarget());
    assertEquals(IntentCode.TRANSFER_FULL, deserialized2.getPayloads().get(0).getIntentCode());
    assertEquals("payload-missing", deserialized2.getPayloads().get(0).getReason());
  }

  @Test
  public void serverIntent_CanDeserializeMultiplePayloads() throws Exception {
    String json = "{\n" +
        "  \"payloads\": [\n" +
        "    {\n" +
        "      \"id\": \"payload-1\",\n" +
        "      \"target\": 10,\n" +
        "      \"intentCode\": \"xfer-changes\",\n" +
        "      \"reason\": \"stale\"\n" +
        "    },\n" +
        "    {\n" +
        "      \"id\": \"payload-2\",\n" +
        "      \"target\": 20,\n" +
        "      \"intentCode\": \"none\",\n" +
        "      \"reason\": \"up-to-date\"\n" +
        "    }\n" +
        "  ]\n" +
        "}";

    ServerIntent serverIntent = ServerIntent.parse(new JsonReader(new StringReader(json)));

    assertNotNull(serverIntent);
    assertEquals(2, serverIntent.getPayloads().size());
    assertEquals("payload-1", serverIntent.getPayloads().get(0).getId());
    assertEquals(IntentCode.TRANSFER_CHANGES, serverIntent.getPayloads().get(0).getIntentCode());
    assertEquals("payload-2", serverIntent.getPayloads().get(1).getId());
    assertEquals(IntentCode.NONE, serverIntent.getPayloads().get(1).getIntentCode());
  }

  @Test
  public void putObject_CanDeserializeWithFlag() throws Exception {
    String json = "{\n" +
        "  \"version\": 10,\n" +
        "  \"kind\": \"flag\",\n" +
        "  \"key\": \"test-flag\",\n" +
        "  \"object\": {\n" +
        "    \"key\": \"test-flag\",\n" +
        "    \"version\": 5,\n" +
        "    \"on\": true,\n" +
        "    \"fallthrough\": { \"variation\": 0 },\n" +
        "    \"offVariation\": 1,\n" +
        "    \"variations\": [true, false],\n" +
        "    \"salt\": \"abc123\",\n" +
        "    \"trackEvents\": false,\n" +
        "    \"trackEventsFallthrough\": false,\n" +
        "    \"debugEventsUntilDate\": null,\n" +
        "    \"clientSide\": true,\n" +
        "    \"deleted\": false\n" +
        "  }\n" +
        "}";

    PutObject putObject = PutObject.parse(new JsonReader(new StringReader(json)));

    assertNotNull(putObject);
    assertEquals(10, putObject.getVersion());
    assertEquals("flag", putObject.getKind());
    assertEquals("test-flag", putObject.getKey());

    // Verify the object JsonElement contains the expected flag data
    JsonElement objectElement = putObject.getObject();
    assertNotNull(objectElement);
    assertTrue(objectElement.isJsonObject());
    assertEquals("test-flag", objectElement.getAsJsonObject().get("key").getAsString());
    assertEquals(5, objectElement.getAsJsonObject().get("version").getAsInt());
    assertTrue(objectElement.getAsJsonObject().get("on").getAsBoolean());
    assertEquals("abc123", objectElement.getAsJsonObject().get("salt").getAsString());
  }

  @Test
  public void putObject_CanReserializeWithFlag() throws Exception {
    // Create a flag JSON
    String flagJson = "{\n" +
        "  \"key\": \"my-flag\",\n" +
        "  \"version\": 3,\n" +
        "  \"on\": true,\n" +
        "  \"fallthrough\": { \"variation\": 0 },\n" +
        "  \"offVariation\": 1,\n" +
        "  \"variations\": [true, false],\n" +
        "  \"salt\": \"salt123\",\n" +
        "  \"clientSide\": true,\n" +
        "  \"deleted\": false\n" +
        "}";

    JsonElement flagElement = gsonInstance().fromJson(flagJson, JsonElement.class);
    PutObject putObject = new PutObject(15, "flag", "my-flag", flagElement);

    String serialized = gsonInstance().toJson(putObject);
    PutObject deserialized = PutObject.parse(new JsonReader(new StringReader(serialized)));

    assertEquals(15, deserialized.getVersion());
    assertEquals("flag", deserialized.getKind());
    assertEquals("my-flag", deserialized.getKey());

    JsonElement deserializedFlagElement = deserialized.getObject();
    assertEquals("my-flag", deserializedFlagElement.getAsJsonObject().get("key").getAsString());
    assertEquals(3, deserializedFlagElement.getAsJsonObject().get("version").getAsInt());
    assertTrue(deserializedFlagElement.getAsJsonObject().get("on").getAsBoolean());
    assertEquals("salt123", deserializedFlagElement.getAsJsonObject().get("salt").getAsString());
    assertTrue(deserializedFlagElement.getAsJsonObject().get("clientSide").getAsBoolean());
    assertEquals(0, deserializedFlagElement.getAsJsonObject().get("fallthrough")
        .getAsJsonObject().get("variation").getAsInt());
    assertEquals(1, deserializedFlagElement.getAsJsonObject().get("offVariation").getAsInt());
    assertEquals(2, deserializedFlagElement.getAsJsonObject().get("variations").getAsJsonArray().size());
    assertTrue(deserializedFlagElement.getAsJsonObject().get("variations").getAsJsonArray().get(0).getAsBoolean());
  }

  @Test
  public void putObject_CanDeserializeWithSegment() throws Exception {
    String json = "{\n" +
        "  \"version\": 20,\n" +
        "  \"kind\": \"segment\",\n" +
        "  \"key\": \"test-segment\",\n" +
        "  \"object\": {\n" +
        "    \"key\": \"test-segment\",\n" +
        "    \"version\": 7,\n" +
        "    \"included\": [\"user1\", \"user2\"],\n" +
        "    \"salt\": \"seg-salt\",\n" +
        "    \"deleted\": false\n" +
        "  }\n" +
        "}";

    PutObject putObject = PutObject.parse(new JsonReader(new StringReader(json)));

    assertNotNull(putObject);
    assertEquals(20, putObject.getVersion());
    assertEquals("segment", putObject.getKind());
    assertEquals("test-segment", putObject.getKey());

    // Verify the object JsonElement contains the expected segment data
    JsonElement objectElement = putObject.getObject();
    assertNotNull(objectElement);
    assertTrue(objectElement.isJsonObject());
    assertEquals("test-segment", objectElement.getAsJsonObject().get("key").getAsString());
    assertEquals(7, objectElement.getAsJsonObject().get("version").getAsInt());
    assertEquals(2, objectElement.getAsJsonObject().get("included").getAsJsonArray().size());
    assertTrue(objectElement.getAsJsonObject().get("included").getAsJsonArray().toString().contains("user1"));
    assertTrue(objectElement.getAsJsonObject().get("included").getAsJsonArray().toString().contains("user2"));
  }

  @Test
  public void putObject_CanReserializeWithSegment() throws Exception {
    // Create a segment JSON
    String segmentJson = "{\n" +
        "  \"key\": \"my-segment\",\n" +
        "  \"version\": 5,\n" +
        "  \"included\": [\"alice\", \"bob\"],\n" +
        "  \"salt\": \"segment-salt\",\n" +
        "  \"deleted\": false\n" +
        "}";

    JsonElement segmentElement = gsonInstance().fromJson(segmentJson, JsonElement.class);
    PutObject putObject = new PutObject(25, "segment", "my-segment", segmentElement);

    String serialized = gsonInstance().toJson(putObject);
    PutObject deserialized = PutObject.parse(new JsonReader(new StringReader(serialized)));

    assertEquals(25, deserialized.getVersion());
    assertEquals("segment", deserialized.getKind());
    assertEquals("my-segment", deserialized.getKey());

    JsonElement deserializedSegmentElement = deserialized.getObject();
    assertEquals("my-segment", deserializedSegmentElement.getAsJsonObject().get("key").getAsString());
    assertEquals(5, deserializedSegmentElement.getAsJsonObject().get("version").getAsInt());
    assertEquals(2, deserializedSegmentElement.getAsJsonObject().get("included").getAsJsonArray().size());
    assertTrue(deserializedSegmentElement.getAsJsonObject().get("included").getAsJsonArray().toString().contains("alice"));
    assertTrue(deserializedSegmentElement.getAsJsonObject().get("included").getAsJsonArray().toString().contains("bob"));
    assertEquals("segment-salt", deserializedSegmentElement.getAsJsonObject().get("salt").getAsString());
  }

  @Test
  public void deleteObject_CanDeserializeAndReserialize() throws Exception {
    String json = "{\n" +
        "  \"version\": 30,\n" +
        "  \"kind\": \"flag\",\n" +
        "  \"key\": \"deleted-flag\"\n" +
        "}";

    DeleteObject deleteObject = DeleteObject.parse(new JsonReader(new StringReader(json)));

    assertNotNull(deleteObject);
    assertEquals(30, deleteObject.getVersion());
    assertEquals("flag", deleteObject.getKind());
    assertEquals("deleted-flag", deleteObject.getKey());

    // Reserialize
    String reserialized = gsonInstance().toJson(deleteObject);
    DeleteObject deserialized2 = DeleteObject.parse(new JsonReader(new StringReader(reserialized)));
    assertEquals(30, deserialized2.getVersion());
    assertEquals("flag", deserialized2.getKind());
    assertEquals("deleted-flag", deserialized2.getKey());
  }

  @Test
  public void deleteObject_CanDeserializeSegment() throws Exception {
    String json = "{\n" +
        "  \"version\": 12,\n" +
        "  \"kind\": \"segment\",\n" +
        "  \"key\": \"removed-segment\"\n" +
        "}";

    DeleteObject deleteObject = DeleteObject.parse(new JsonReader(new StringReader(json)));

    assertEquals(12, deleteObject.getVersion());
    assertEquals("segment", deleteObject.getKind());
    assertEquals("removed-segment", deleteObject.getKey());
  }

  @Test
  public void payloadTransferred_CanDeserializeAndReserialize() throws Exception {
    String json = "{\n" +
        "  \"state\": \"(p:ABC123:42)\",\n" +
        "  \"version\": 42\n" +
        "}";

    PayloadTransferred payloadTransferred = PayloadTransferred.parse(new JsonReader(new StringReader(json)));

    assertNotNull(payloadTransferred);
    assertEquals("(p:ABC123:42)", payloadTransferred.getState());
    assertEquals(42, payloadTransferred.getVersion());

    // Reserialize
    String reserialized = gsonInstance().toJson(payloadTransferred);
    PayloadTransferred deserialized2 = PayloadTransferred.parse(new JsonReader(new StringReader(reserialized)));
    assertEquals("(p:ABC123:42)", deserialized2.getState());
    assertEquals(42, deserialized2.getVersion());
  }

  @Test
  public void error_CanDeserializeAndReserialize() throws Exception {
    String json = "{\n" +
        "  \"id\": \"error-123\",\n" +
        "  \"reason\": \"Something went wrong\"\n" +
        "}";

    Error error = Error.parse(new JsonReader(new StringReader(json)));

    assertNotNull(error);
    assertEquals("error-123", error.getId());
    assertEquals("Something went wrong", error.getReason());

    // Reserialize
    String reserialized = gsonInstance().toJson(error);
    Error deserialized2 = Error.parse(new JsonReader(new StringReader(reserialized)));
    assertEquals("error-123", deserialized2.getId());
    assertEquals("Something went wrong", deserialized2.getReason());
  }

  @Test
  public void goodbye_CanDeserializeAndReserialize() throws Exception {
    String json = "{\n" +
        "  \"reason\": \"Server is shutting down\"\n" +
        "}";

    Goodbye goodbye = Goodbye.parse(new JsonReader(new StringReader(json)));

    assertNotNull(goodbye);
    assertEquals("Server is shutting down", goodbye.getReason());

    // Reserialize
    String reserialized = gsonInstance().toJson(goodbye);
    Goodbye deserialized2 = Goodbye.parse(new JsonReader(new StringReader(reserialized)));
    assertEquals("Server is shutting down", deserialized2.getReason());
  }

  @Test
  public void fDv2PollEvent_CanDeserializeServerIntent() throws Exception {
    String json = "{\n" +
        "  \"event\": \"server-intent\",\n" +
        "  \"data\": {\n" +
        "    \"payloads\": [\n" +
        "      {\n" +
        "        \"id\": \"evt-123\",\n" +
        "        \"target\": 50,\n" +
        "        \"intentCode\": \"xfer-full\",\n" +
        "        \"reason\": \"payload-missing\"\n" +
        "      }\n" +
        "    ]\n" +
        "  }\n" +
        "}";

    FDv2Event pollEvent = FDv2Event.parse(new JsonReader(new StringReader(json)));

    assertNotNull(pollEvent);
    assertEquals("server-intent", pollEvent.getEventType());

    ServerIntent serverIntent = pollEvent.asServerIntent();
    assertNotNull(serverIntent);
    assertEquals(1, serverIntent.getPayloads().size());
    assertEquals("evt-123", serverIntent.getPayloads().get(0).getId());
    assertEquals(50, serverIntent.getPayloads().get(0).getTarget());
  }

  @Test
  public void fDv2PollEvent_CanDeserializePutObject() throws Exception {
    String json = "{\n" +
        "  \"event\": \"put-object\",\n" +
        "  \"data\": {\n" +
        "    \"version\": 100,\n" +
        "    \"kind\": \"flag\",\n" +
        "    \"key\": \"event-flag\",\n" +
        "    \"object\": {\n" +
        "      \"key\": \"event-flag\",\n" +
        "      \"version\": 1,\n" +
        "      \"on\": false,\n" +
        "      \"fallthrough\": { \"variation\": 1 },\n" +
        "      \"offVariation\": 1,\n" +
        "      \"variations\": [\"A\", \"B\", \"C\"],\n" +
        "      \"salt\": \"evt-salt\",\n" +
        "      \"trackEvents\": false,\n" +
        "      \"trackEventsFallthrough\": false,\n" +
        "      \"debugEventsUntilDate\": null,\n" +
        "      \"clientSide\": false,\n" +
        "      \"deleted\": false\n" +
        "    }\n" +
        "  }\n" +
        "}";

    FDv2Event pollEvent = FDv2Event.parse(new JsonReader(new StringReader(json)));

    assertNotNull(pollEvent);
    assertEquals("put-object", pollEvent.getEventType());

    PutObject putObject = pollEvent.asPutObject();
    assertNotNull(putObject);
    assertEquals(100, putObject.getVersion());
    assertEquals("flag", putObject.getKind());
    assertEquals("event-flag", putObject.getKey());

    JsonElement flagElement = putObject.getObject();
    assertEquals("event-flag", flagElement.getAsJsonObject().get("key").getAsString());
    assertTrue(!flagElement.getAsJsonObject().get("on").getAsBoolean());
    assertEquals(3, flagElement.getAsJsonObject().get("variations").getAsJsonArray().size());
  }

  @Test
  public void fDv2PollEvent_CanDeserializeDeleteObject() throws Exception {
    String json = "{\n" +
        "  \"event\": \"delete-object\",\n" +
        "  \"data\": {\n" +
        "    \"version\": 99,\n" +
        "    \"kind\": \"segment\",\n" +
        "    \"key\": \"old-segment\"\n" +
        "  }\n" +
        "}";

    FDv2Event pollEvent = FDv2Event.parse(new JsonReader(new StringReader(json)));

    assertEquals("delete-object", pollEvent.getEventType());

    DeleteObject deleteObject = pollEvent.asDeleteObject();
    assertEquals(99, deleteObject.getVersion());
    assertEquals("segment", deleteObject.getKind());
    assertEquals("old-segment", deleteObject.getKey());
  }

  @Test
  public void fDv2PollEvent_CanDeserializePayloadTransferred() throws Exception {
    String json = "{\n" +
        "  \"event\": \"payload-transferred\",\n" +
        "  \"data\": {\n" +
        "    \"state\": \"(p:XYZ789:100)\",\n" +
        "    \"version\": 100\n" +
        "  }\n" +
        "}";

    FDv2Event pollEvent = FDv2Event.parse(new JsonReader(new StringReader(json)));

    assertEquals("payload-transferred", pollEvent.getEventType());

    PayloadTransferred payloadTransferred = pollEvent.asPayloadTransferred();
    assertEquals("(p:XYZ789:100)", payloadTransferred.getState());
    assertEquals(100, payloadTransferred.getVersion());
  }

  @Test(expected = SerializationException.class)
  public void serverIntent_ThrowsWhenPayloadsFieldMissing() throws Exception {
    String json = "{}";
    ServerIntent.parse(new JsonReader(new StringReader(json)));
  }

  @Test(expected = SerializationException.class)
  public void serverIntent_ThrowsWhenPayloadIdFieldMissing() throws Exception {
    String json = "{\n" +
        "  \"payloads\": [\n" +
        "    {\n" +
        "      \"target\": 42,\n" +
        "      \"intentCode\": \"xfer-full\",\n" +
        "      \"reason\": \"payload-missing\"\n" +
        "    }\n" +
        "  ]\n" +
        "}";
    ServerIntent.parse(new JsonReader(new StringReader(json)));
  }

  @Test(expected = SerializationException.class)
  public void serverIntent_ThrowsWhenPayloadTargetFieldMissing() throws Exception {
    String json = "{\n" +
        "  \"payloads\": [\n" +
        "    {\n" +
        "      \"id\": \"payload-123\",\n" +
        "      \"intentCode\": \"xfer-full\",\n" +
        "      \"reason\": \"payload-missing\"\n" +
        "    }\n" +
        "  ]\n" +
        "}";
    ServerIntent.parse(new JsonReader(new StringReader(json)));
  }

  @Test(expected = SerializationException.class)
  public void serverIntent_ThrowsWhenPayloadIntentCodeFieldMissing() throws Exception {
    String json = "{\n" +
        "  \"payloads\": [\n" +
        "    {\n" +
        "      \"id\": \"payload-123\",\n" +
        "      \"target\": 42,\n" +
        "      \"reason\": \"payload-missing\"\n" +
        "    }\n" +
        "  ]\n" +
        "}";
    ServerIntent.parse(new JsonReader(new StringReader(json)));
  }

  @Test(expected = SerializationException.class)
  public void serverIntent_ThrowsWhenPayloadReasonFieldMissing() throws Exception {
    String json = "{\n" +
        "  \"payloads\": [\n" +
        "    {\n" +
        "      \"id\": \"payload-123\",\n" +
        "      \"target\": 42,\n" +
        "      \"intentCode\": \"xfer-full\"\n" +
        "    }\n" +
        "  ]\n" +
        "}";
    ServerIntent.parse(new JsonReader(new StringReader(json)));
  }

  @Test(expected = SerializationException.class)
  public void putObject_ThrowsWhenVersionFieldMissing() throws Exception {
    String json = "{\n" +
        "  \"kind\": \"flag\",\n" +
        "  \"key\": \"test-flag\",\n" +
        "  \"object\": {}\n" +
        "}";
    PutObject.parse(new JsonReader(new StringReader(json)));
  }

  @Test(expected = SerializationException.class)
  public void putObject_ThrowsWhenKindFieldMissing() throws Exception {
    String json = "{\n" +
        "  \"version\": 10,\n" +
        "  \"key\": \"test-flag\",\n" +
        "  \"object\": {}\n" +
        "}";
    PutObject.parse(new JsonReader(new StringReader(json)));
  }

  @Test(expected = SerializationException.class)
  public void putObject_ThrowsWhenKeyFieldMissing() throws Exception {
    String json = "{\n" +
        "  \"version\": 10,\n" +
        "  \"kind\": \"flag\",\n" +
        "  \"object\": {}\n" +
        "}";
    PutObject.parse(new JsonReader(new StringReader(json)));
  }

  @Test(expected = SerializationException.class)
  public void putObject_ThrowsWhenObjectFieldMissing() throws Exception {
    String json = "{\n" +
        "  \"version\": 10,\n" +
        "  \"kind\": \"flag\",\n" +
        "  \"key\": \"test-flag\"\n" +
        "}";
    PutObject.parse(new JsonReader(new StringReader(json)));
  }

  @Test(expected = SerializationException.class)
  public void deleteObject_ThrowsWhenVersionFieldMissing() throws Exception {
    String json = "{\n" +
        "  \"kind\": \"flag\",\n" +
        "  \"key\": \"test-flag\"\n" +
        "}";
    DeleteObject.parse(new JsonReader(new StringReader(json)));
  }

  @Test(expected = SerializationException.class)
  public void deleteObject_ThrowsWhenKindFieldMissing() throws Exception {
    String json = "{\n" +
        "  \"version\": 30,\n" +
        "  \"key\": \"test-flag\"\n" +
        "}";
    DeleteObject.parse(new JsonReader(new StringReader(json)));
  }

  @Test(expected = SerializationException.class)
  public void deleteObject_ThrowsWhenKeyFieldMissing() throws Exception {
    String json = "{\n" +
        "  \"version\": 30,\n" +
        "  \"kind\": \"flag\"\n" +
        "}";
    DeleteObject.parse(new JsonReader(new StringReader(json)));
  }

  @Test(expected = SerializationException.class)
  public void payloadTransferred_ThrowsWhenStateFieldMissing() throws Exception {
    String json = "{\n" +
        "  \"version\": 42\n" +
        "}";
    PayloadTransferred.parse(new JsonReader(new StringReader(json)));
  }

  @Test(expected = SerializationException.class)
  public void payloadTransferred_ThrowsWhenVersionFieldMissing() throws Exception {
    String json = "{\n" +
        "  \"state\": \"(p:ABC123:42)\"\n" +
        "}";
    PayloadTransferred.parse(new JsonReader(new StringReader(json)));
  }

  @Test(expected = SerializationException.class)
  public void error_ThrowsWhenReasonFieldMissing() throws Exception {
    String json = "{\n" +
        "  \"id\": \"error-123\"\n" +
        "}";
    Error.parse(new JsonReader(new StringReader(json)));
  }

  @Test
  public void goodbye_CanDeserializeWithoutReason() throws Exception {
    // Goodbye has no required fields, so an empty object should be valid
    String json = "{}";
    Goodbye goodbye = Goodbye.parse(new JsonReader(new StringReader(json)));
    assertNotNull(goodbye);
    assertNull(goodbye.getReason());
  }

  @Test(expected = SerializationException.class)
  public void fDv2PollEvent_ThrowsWhenEventFieldMissing() throws Exception {
    String json = "{\n" +
        "  \"data\": {\n" +
        "    \"state\": \"(p:XYZ:100)\",\n" +
        "    \"version\": 100\n" +
        "  }\n" +
        "}";
    FDv2Event.parse(new JsonReader(new StringReader(json)));
  }

  @Test(expected = SerializationException.class)
  public void fDv2PollEvent_ThrowsWhenDataFieldMissing() throws Exception {
    String json = "{\n" +
        "  \"event\": \"payload-transferred\"\n" +
        "}";
    FDv2Event.parse(new JsonReader(new StringReader(json)));
  }

  @Test(expected = NullPointerException.class)
  public void serverIntent_ThrowsArgumentNullExceptionWhenPayloadsIsNull() {
    new ServerIntent(null);
  }

  // Note: ServerIntentPayload constructor is package-private, so we can't test null checks directly.
  // The null checks are tested indirectly through the parsing logic in the tests above.

  @Test(expected = NullPointerException.class)
  public void putObject_ThrowsArgumentNullExceptionWhenKindIsNull() {
    JsonElement emptyObject = gsonInstance().fromJson("{}", JsonElement.class);
    new PutObject(1, null, "key", emptyObject);
  }

  @Test(expected = NullPointerException.class)
  public void putObject_ThrowsArgumentNullExceptionWhenKeyIsNull() {
    JsonElement emptyObject = gsonInstance().fromJson("{}", JsonElement.class);
    new PutObject(1, "flag", null, emptyObject);
  }

  @Test(expected = NullPointerException.class)
  public void deleteObject_ThrowsArgumentNullExceptionWhenKindIsNull() {
    new DeleteObject(1, null, "key");
  }

  @Test(expected = NullPointerException.class)
  public void deleteObject_ThrowsArgumentNullExceptionWhenKeyIsNull() {
    new DeleteObject(1, "flag", null);
  }

  @Test(expected = NullPointerException.class)
  public void payloadTransferred_ThrowsArgumentNullExceptionWhenStateIsNull() {
    new PayloadTransferred(null, 42);
  }

  @Test(expected = NullPointerException.class)
  public void error_ThrowsArgumentNullExceptionWhenReasonIsNull() {
    new Error("id", null);
  }

  @Test
  public void fullPollingResponse_CanDeserialize() throws Exception {
    String json = "{\n" +
        "  \"events\": [\n" +
        "    {\n" +
        "      \"event\": \"server-intent\",\n" +
        "      \"data\": {\n" +
        "        \"payloads\": [{\n" +
        "          \"id\": \"poll-payload-1\",\n" +
        "          \"target\": 200,\n" +
        "          \"intentCode\": \"xfer-full\",\n" +
        "          \"reason\": \"payload-missing\"\n" +
        "        }]\n" +
        "      }\n" +
        "    },\n" +
        "    {\n" +
        "      \"event\": \"put-object\",\n" +
        "      \"data\": {\n" +
        "        \"version\": 150,\n" +
        "        \"kind\": \"flag\",\n" +
        "        \"key\": \"flag-one\",\n" +
        "        \"object\": {\n" +
        "          \"key\": \"flag-one\",\n" +
        "          \"version\": 1,\n" +
        "          \"on\": true,\n" +
        "          \"fallthrough\": { \"variation\": 0 },\n" +
        "          \"offVariation\": 1,\n" +
        "          \"variations\": [true, false],\n" +
        "          \"salt\": \"flag-one-salt\",\n" +
        "          \"trackEvents\": false,\n" +
        "          \"trackEventsFallthrough\": false,\n" +
        "          \"debugEventsUntilDate\": null,\n" +
        "          \"clientSide\": true,\n" +
        "          \"deleted\": false\n" +
        "        }\n" +
        "      }\n" +
        "    },\n" +
        "    {\n" +
        "      \"event\": \"put-object\",\n" +
        "      \"data\": {\n" +
        "        \"version\": 160,\n" +
        "        \"kind\": \"segment\",\n" +
        "        \"key\": \"segment-one\",\n" +
        "        \"object\": {\n" +
        "          \"key\": \"segment-one\",\n" +
        "          \"version\": 2,\n" +
        "          \"included\": [\"user-a\", \"user-b\"],\n" +
        "          \"salt\": \"seg-salt\",\n" +
        "          \"deleted\": false\n" +
        "        }\n" +
        "      }\n" +
        "    },\n" +
        "    {\n" +
        "      \"event\": \"delete-object\",\n" +
        "      \"data\": {\n" +
        "        \"version\": 170,\n" +
        "        \"kind\": \"flag\",\n" +
        "        \"key\": \"old-flag\"\n" +
        "      }\n" +
        "    },\n" +
        "    {\n" +
        "      \"event\": \"payload-transferred\",\n" +
        "      \"data\": {\n" +
        "        \"state\": \"(p:poll-payload-1:200)\",\n" +
        "        \"version\": 200\n" +
        "      }\n" +
        "    }\n" +
        "  ]\n" +
        "}";

    // Parse the polling response
    List<FDv2Event> eventsList = FDv2Event.parseEventsArray(json);

    assertNotNull(eventsList);
    assertEquals(5, eventsList.size());

    // Verify server-intent
    assertEquals("server-intent", eventsList.get(0).getEventType());
    ServerIntent serverIntent = eventsList.get(0).asServerIntent();
    assertEquals("poll-payload-1", serverIntent.getPayloads().get(0).getId());
    assertEquals(200, serverIntent.getPayloads().get(0).getTarget());

    // Verify first put-object (flag)
    assertEquals("put-object", eventsList.get(1).getEventType());
    PutObject putFlag = eventsList.get(1).asPutObject();
    assertEquals("flag", putFlag.getKind());
    assertEquals("flag-one", putFlag.getKey());
    JsonElement flagElement = putFlag.getObject();
    assertEquals("flag-one", flagElement.getAsJsonObject().get("key").getAsString());
    assertTrue(flagElement.getAsJsonObject().get("on").getAsBoolean());

    // Verify second put-object (segment)
    assertEquals("put-object", eventsList.get(2).getEventType());
    PutObject putSegment = eventsList.get(2).asPutObject();
    assertEquals("segment", putSegment.getKind());
    assertEquals("segment-one", putSegment.getKey());
    JsonElement segmentElement = putSegment.getObject();
    assertEquals("segment-one", segmentElement.getAsJsonObject().get("key").getAsString());
    assertEquals(2, segmentElement.getAsJsonObject().get("included").getAsJsonArray().size());

    // Verify delete-object
    assertEquals("delete-object", eventsList.get(3).getEventType());
    DeleteObject deleteObj = eventsList.get(3).asDeleteObject();
    assertEquals("flag", deleteObj.getKind());
    assertEquals("old-flag", deleteObj.getKey());

    // Verify payload-transferred
    assertEquals("payload-transferred", eventsList.get(4).getEventType());
    PayloadTransferred transferred = eventsList.get(4).asPayloadTransferred();
    assertEquals("(p:poll-payload-1:200)", transferred.getState());
    assertEquals(200, transferred.getVersion());
  }

  @Test(expected = SerializationException.class)
  public void deserializeEventsArray_ThrowsWhenEventsPropertyMissing() throws Exception {
    String json = "{}";
    FDv2Event.parseEventsArray(json);
  }

  @Test(expected = SerializationException.class)
  public void deserializeEventsArray_ThrowsWhenEventIsNull() throws Exception {
    String json = "{\n" +
        "  \"events\": [\n" +
        "    {\n" +
        "      \"event\": \"server-intent\",\n" +
        "      \"data\": {\n" +
        "        \"payloads\": [{\n" +
        "          \"id\": \"payload-1\",\n" +
        "          \"target\": 100,\n" +
        "          \"intentCode\": \"xfer-full\",\n" +
        "          \"reason\": \"payload-missing\"\n" +
        "        }]\n" +
        "      }\n" +
        "    },\n" +
        "    null,\n" +
        "    {\n" +
        "      \"event\": \"heartbeat\",\n" +
        "      \"data\": {}\n" +
        "    }\n" +
        "  ]\n" +
        "}";

    FDv2Event.parseEventsArray(json);
  }

  @Test
  public void deserializeEventsArray_CanDeserializeEmptyArray() throws Exception {
    String json = "{\n" +
        "  \"events\": []\n" +
        "}";

    List<FDv2Event> events = FDv2Event.parseEventsArray(json);
    assertNotNull(events);
    assertTrue(events.isEmpty());
  }

  @Test
  public void deserializeEventsArray_CanDeserializeValidEventsArray() throws Exception {
    String json = "{\n" +
        "  \"events\": [\n" +
        "    {\n" +
        "      \"event\": \"server-intent\",\n" +
        "      \"data\": {\n" +
        "        \"payloads\": [{\n" +
        "          \"id\": \"payload-1\",\n" +
        "          \"target\": 100,\n" +
        "          \"intentCode\": \"xfer-full\",\n" +
        "          \"reason\": \"payload-missing\"\n" +
        "        }]\n" +
        "      }\n" +
        "    },\n" +
        "    {\n" +
        "      \"event\": \"put-object\",\n" +
        "      \"data\": {\n" +
        "        \"version\": 150,\n" +
        "        \"kind\": \"flag\",\n" +
        "        \"key\": \"test-flag\",\n" +
        "        \"object\": {\n" +
        "          \"key\": \"test-flag\",\n" +
        "          \"version\": 1,\n" +
        "          \"on\": true,\n" +
        "          \"fallthrough\": { \"variation\": 0 },\n" +
        "          \"offVariation\": 1,\n" +
        "          \"variations\": [true, false],\n" +
        "          \"salt\": \"test-salt\",\n" +
        "          \"trackEvents\": false,\n" +
        "          \"trackEventsFallthrough\": false,\n" +
        "          \"debugEventsUntilDate\": null,\n" +
        "          \"clientSide\": false,\n" +
        "          \"deleted\": false\n" +
        "        }\n" +
        "      }\n" +
        "    },\n" +
        "    {\n" +
        "      \"event\": \"payload-transferred\",\n" +
        "      \"data\": {\n" +
        "        \"state\": \"(p:payload-1:100)\",\n" +
        "        \"version\": 100\n" +
        "      }\n" +
        "    }\n" +
        "  ]\n" +
        "}";

    List<FDv2Event> events = FDv2Event.parseEventsArray(json);

    assertNotNull(events);
    assertEquals(3, events.size());

    assertEquals("server-intent", events.get(0).getEventType());
    ServerIntent serverIntent = events.get(0).asServerIntent();
    assertEquals("payload-1", serverIntent.getPayloads().get(0).getId());

    assertEquals("put-object", events.get(1).getEventType());
    PutObject putObject = events.get(1).asPutObject();
    assertEquals("test-flag", putObject.getKey());

    assertEquals("payload-transferred", events.get(2).getEventType());
    PayloadTransferred transferred = events.get(2).asPayloadTransferred();
    assertEquals(100, transferred.getVersion());
  }
}

