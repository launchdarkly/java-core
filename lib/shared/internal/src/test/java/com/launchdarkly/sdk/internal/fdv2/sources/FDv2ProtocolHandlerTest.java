package com.launchdarkly.sdk.internal.fdv2.sources;

import com.google.gson.JsonElement;
import com.launchdarkly.sdk.internal.BaseInternalTest;
import com.launchdarkly.sdk.internal.fdv2.payloads.DeleteObject;
import com.launchdarkly.sdk.internal.fdv2.payloads.Error;
import com.launchdarkly.sdk.internal.fdv2.payloads.FDv2Event;
import com.launchdarkly.sdk.internal.fdv2.payloads.FDv2Event.FDv2EventTypeMismatchException;
import com.launchdarkly.sdk.internal.fdv2.payloads.Goodbye;
import com.launchdarkly.sdk.internal.fdv2.payloads.IntentCode;
import com.launchdarkly.sdk.internal.fdv2.payloads.PayloadTransferred;
import com.launchdarkly.sdk.internal.fdv2.payloads.PutObject;
import com.launchdarkly.sdk.internal.fdv2.payloads.ServerIntent;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.launchdarkly.sdk.internal.GsonHelpers.gsonInstance;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@SuppressWarnings("javadoc")
public class FDv2ProtocolHandlerTest extends BaseInternalTest {

  private static FDv2Event createServerIntentEvent(IntentCode intentCode, String payloadId, int target, String reason) {
    List<ServerIntent.ServerIntentPayload> payloads = Collections.singletonList(
        new ServerIntent.ServerIntentPayload(payloadId, target, intentCode, reason));
    ServerIntent intent = new ServerIntent(payloads);
    String json = gsonInstance().toJson(intent);
    JsonElement data = gsonInstance().fromJson(json, JsonElement.class);
    return new FDv2Event(FDv2EventTypes.SERVER_INTENT, data);
  }

  private static FDv2Event createServerIntentEvent(IntentCode intentCode) {
    return createServerIntentEvent(intentCode, "test-payload", 1, "test-reason");
  }

  private static FDv2Event createPutObjectEvent(String kind, String key, int version, String jsonStr) {
    JsonElement objectElement = gsonInstance().fromJson(jsonStr, JsonElement.class);
    PutObject putObj = new PutObject(version, kind, key, objectElement);
    String json = gsonInstance().toJson(putObj);
    JsonElement data = gsonInstance().fromJson(json, JsonElement.class);
    return new FDv2Event(FDv2EventTypes.PUT_OBJECT, data);
  }

  private static FDv2Event createPutObjectEvent(String kind, String key, int version) {
    return createPutObjectEvent(kind, key, version, "{}");
  }

  private static FDv2Event createDeleteObjectEvent(String kind, String key, int version) {
    DeleteObject deleteObj = new DeleteObject(version, kind, key);
    String json = gsonInstance().toJson(deleteObj);
    JsonElement data = gsonInstance().fromJson(json, JsonElement.class);
    return new FDv2Event(FDv2EventTypes.DELETE_OBJECT, data);
  }

  private static FDv2Event createPayloadTransferredEvent(String state, int version) {
    PayloadTransferred transferred = new PayloadTransferred(state, version);
    String json = gsonInstance().toJson(transferred);
    JsonElement data = gsonInstance().fromJson(json, JsonElement.class);
    return new FDv2Event(FDv2EventTypes.PAYLOAD_TRANSFERRED, data);
  }

  private static FDv2Event createErrorEvent(String id, String reason) {
    Error error = new Error(id, reason);
    String json = gsonInstance().toJson(error);
    JsonElement data = gsonInstance().fromJson(json, JsonElement.class);
    return new FDv2Event(FDv2EventTypes.ERROR, data);
  }

  private static FDv2Event createGoodbyeEvent(String reason) {
    Goodbye goodbye = new Goodbye(reason);
    String json = gsonInstance().toJson(goodbye);
    JsonElement data = gsonInstance().fromJson(json, JsonElement.class);
    return new FDv2Event(FDv2EventTypes.GOODBYE, data);
  }

  private static FDv2Event createHeartbeatEvent() {
    JsonElement data = gsonInstance().fromJson("{}", JsonElement.class);
    return new FDv2Event(FDv2EventTypes.HEARTBEAT, data);
  }

  // Section 2.2.2: SDK has up to date saved payload

  /**
   * Tests the scenario from section 2.2.2 where the SDK has an up-to-date payload.
   * The server responds with intentCode: none indicating no changes are needed.
   */
  @Test
  public void serverIntent_WithIntentCodeNone_ReturnsChangesetImmediately() {
    // Section 2.2.2: SDK has up to date saved payload
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();
    FDv2Event evt = createServerIntentEvent(IntentCode.NONE, "payload-123", 52, "up-to-date");

    FDv2ProtocolHandler.IFDv2ProtocolAction action = handler.handleEvent(evt);

    assertTrue(action instanceof FDv2ProtocolHandler.FDv2ActionChangeset);
    FDv2ProtocolHandler.FDv2ActionChangeset changesetAction = (FDv2ProtocolHandler.FDv2ActionChangeset) action;
    assertEquals(FDv2ChangeSet.FDv2ChangeSetType.NONE, changesetAction.getChangeset().getType());
    assertTrue(changesetAction.getChangeset().getChanges().isEmpty());
  }

  // Section 2.1.1 & 2.2.1: SDK has no saved payload (Full Transfer)

  /**
   * Tests the scenario from sections 2.1.1 and 2.2.1 where the SDK has no saved payload.
   * The server responds with intentCode: xfer-full and sends a complete payload.
   */
  @Test
  public void fullTransfer_AccumulatesChangesAndEmitsOnPayloadTransferred() {
    // Section 2.1.1 & 2.2.1: SDK has no saved payload and continues to get changes
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();

    // Server-intent with xfer-full
    FDv2Event intentEvt = createServerIntentEvent(IntentCode.TRANSFER_FULL, "payload-123", 52, "payload-missing");
    FDv2ProtocolHandler.IFDv2ProtocolAction intentAction = handler.handleEvent(intentEvt);
    assertTrue(intentAction instanceof FDv2ProtocolHandler.FDv2ActionNone);

    // Put some objects
    FDv2Event put1 = createPutObjectEvent("flag", "flag-123", 12);
    FDv2ProtocolHandler.IFDv2ProtocolAction put1Action = handler.handleEvent(put1);
    assertTrue(put1Action instanceof FDv2ProtocolHandler.FDv2ActionNone);

    FDv2Event put2 = createPutObjectEvent("flag", "flag-abc", 12);
    FDv2ProtocolHandler.IFDv2ProtocolAction put2Action = handler.handleEvent(put2);
    assertTrue(put2Action instanceof FDv2ProtocolHandler.FDv2ActionNone);

    // Payload-transferred finalizes the changeset
    FDv2Event transferredEvt = createPayloadTransferredEvent("(p:payload-123:52)", 52);
    FDv2ProtocolHandler.IFDv2ProtocolAction transferredAction = handler.handleEvent(transferredEvt);

    assertTrue(transferredAction instanceof FDv2ProtocolHandler.FDv2ActionChangeset);
    FDv2ProtocolHandler.FDv2ActionChangeset changesetAction = (FDv2ProtocolHandler.FDv2ActionChangeset) transferredAction;
    assertEquals(FDv2ChangeSet.FDv2ChangeSetType.FULL, changesetAction.getChangeset().getType());
    assertEquals(2, changesetAction.getChangeset().getChanges().size());
    assertEquals("flag-123", changesetAction.getChangeset().getChanges().get(0).getKey());
    assertEquals("flag-abc", changesetAction.getChangeset().getChanges().get(1).getKey());
    assertEquals("(p:payload-123:52)", changesetAction.getChangeset().getSelector().getState());
    assertEquals(52, changesetAction.getChangeset().getSelector().getVersion());
  }

  /**
   * Tests that a full transfer properly replaces any partial state.
   * Requirement 3.3.1: SDK must prepare to fully replace its local payload representation.
   */
  @Test
  public void fullTransfer_ReplacesPartialState() {
    // Requirement 3.3.1: Prepare to fully replace local payload representation
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();

    // Start with an intent to transfer changes
    handler.handleEvent(createServerIntentEvent(IntentCode.TRANSFER_CHANGES, "p1", 1, "stale"));
    handler.handleEvent(createPutObjectEvent("flag", "flag-1", 1));

    // Now receive xfer-full - should replace/reset
    FDv2Event fullIntent = createServerIntentEvent(IntentCode.TRANSFER_FULL, "p1", 2, "outdated");
    handler.handleEvent(fullIntent);

    // Send new full payload
    handler.handleEvent(createPutObjectEvent("flag", "flag-2", 2));
    FDv2ProtocolHandler.IFDv2ProtocolAction action = handler.handleEvent(createPayloadTransferredEvent("(p:p1:2)", 2));

    FDv2ProtocolHandler.FDv2ActionChangeset changesetAction = (FDv2ProtocolHandler.FDv2ActionChangeset) action;
    assertEquals(FDv2ChangeSet.FDv2ChangeSetType.FULL, changesetAction.getChangeset().getType());
    // Should only have flag-2, not flag-1
    assertEquals(1, changesetAction.getChangeset().getChanges().size());
    assertEquals("flag-2", changesetAction.getChangeset().getChanges().get(0).getKey());
  }

  // Section 2.1.2 & 2.2.3: SDK has stale saved payload (Incremental Changes)

  /**
   * Tests the scenario from sections 2.1.2 and 2.2.3 where the SDK has a stale payload.
   * The server responds with intentCode: xfer-changes and sends incremental updates.
   */
  @Test
  public void incrementalTransfer_AccumulatesChangesAndEmitsOnPayloadTransferred() {
    // Section 2.1.2 & 2.2.3: SDK has stale saved payload
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();

    // Server-intent with xfer-changes
    FDv2Event intentEvt = createServerIntentEvent(IntentCode.TRANSFER_CHANGES, "payload-123", 52, "stale");
    FDv2ProtocolHandler.IFDv2ProtocolAction intentAction = handler.handleEvent(intentEvt);
    assertTrue(intentAction instanceof FDv2ProtocolHandler.FDv2ActionNone);

    // Put and delete objects
    FDv2Event put1 = createPutObjectEvent("flag", "flag-cat", 13);
    handler.handleEvent(put1);

    FDv2Event put2 = createPutObjectEvent("flag", "flag-dog", 13);
    handler.handleEvent(put2);

    FDv2Event delete1 = createDeleteObjectEvent("flag", "flag-bat", 13);
    handler.handleEvent(delete1);

    FDv2Event put3 = createPutObjectEvent("flag", "flag-cow", 14);
    handler.handleEvent(put3);

    // Payload-transferred finalizes the changeset
    FDv2Event transferredEvt = createPayloadTransferredEvent("(p:payload-123:52)", 52);
    FDv2ProtocolHandler.IFDv2ProtocolAction transferredAction = handler.handleEvent(transferredEvt);

    assertTrue(transferredAction instanceof FDv2ProtocolHandler.FDv2ActionChangeset);
    FDv2ProtocolHandler.FDv2ActionChangeset changesetAction = (FDv2ProtocolHandler.FDv2ActionChangeset) transferredAction;
    assertEquals(FDv2ChangeSet.FDv2ChangeSetType.PARTIAL, changesetAction.getChangeset().getType());
    assertEquals(4, changesetAction.getChangeset().getChanges().size());
    assertEquals(FDv2ChangeSet.FDv2ChangeType.PUT, changesetAction.getChangeset().getChanges().get(0).getType());
    assertEquals("flag-cat", changesetAction.getChangeset().getChanges().get(0).getKey());
    assertEquals(FDv2ChangeSet.FDv2ChangeType.PUT, changesetAction.getChangeset().getChanges().get(1).getType());
    assertEquals("flag-dog", changesetAction.getChangeset().getChanges().get(1).getKey());
    assertEquals(FDv2ChangeSet.FDv2ChangeType.DELETE, changesetAction.getChangeset().getChanges().get(2).getType());
    assertEquals("flag-bat", changesetAction.getChangeset().getChanges().get(2).getKey());
    assertEquals(FDv2ChangeSet.FDv2ChangeType.PUT, changesetAction.getChangeset().getChanges().get(3).getType());
    assertEquals("flag-cow", changesetAction.getChangeset().getChanges().get(3).getKey());
  }

  // Requirement 3.3.2: Payload State Validity

  /**
   * Requirement 3.3.2: SDK must not consider its local payload state X as valid until
   * receiving the payload-transferred event for the corresponding payload state X.
   */
  @Test
  public void payloadTransferred_OnlyEmitsChangesetAfterReceivingEvent() {
    // Requirement 3.3.2: Only consider payload valid after payload-transferred
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();

    handler.handleEvent(createServerIntentEvent(IntentCode.TRANSFER_FULL, "p1", 1, "missing"));

    // Accumulate changes - should not emit changeset yet
    FDv2ProtocolHandler.IFDv2ProtocolAction action1 = handler.handleEvent(createPutObjectEvent("flag", "f1", 1));
    assertTrue(action1 instanceof FDv2ProtocolHandler.FDv2ActionNone);

    FDv2ProtocolHandler.IFDv2ProtocolAction action2 = handler.handleEvent(createPutObjectEvent("flag", "f2", 1));
    assertTrue(action2 instanceof FDv2ProtocolHandler.FDv2ActionNone);

    // Only after payload-transferred should we get a changeset
    FDv2ProtocolHandler.IFDv2ProtocolAction action3 = handler.handleEvent(createPayloadTransferredEvent("(p:p1:1)", 1));
    assertTrue(action3 instanceof FDv2ProtocolHandler.FDv2ActionChangeset);
  }

  /**
   * Tests that payload-transferred event returns protocol error if received without prior server-intent.
   */
  @Test
  public void payloadTransferred_WithoutServerIntent_ReturnsProtocolError() {
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();

    // Attempt to send payload-transferred without server-intent
    FDv2Event transferredEvt = createPayloadTransferredEvent("(p:payload-123:52)", 52);

    FDv2ProtocolHandler.IFDv2ProtocolAction action = handler.handleEvent(transferredEvt);
    assertTrue(action instanceof FDv2ProtocolHandler.FDv2ActionInternalError);
    FDv2ProtocolHandler.FDv2ActionInternalError internalError = (FDv2ProtocolHandler.FDv2ActionInternalError) action;
    assertEquals(FDv2ProtocolHandler.FDv2ProtocolErrorType.PROTOCOL_ERROR, internalError.getErrorType());
    assertTrue(internalError.getMessage().contains("without an intent"));
  }

  // Requirement 3.3.7 & 3.3.8: Error Handling

  /**
   * Requirement 3.3.7: SDK must discard partially transferred data when an error event is encountered.
   * Requirement 3.3.8: SDK should stay connected after receiving an application level error event.
   */
  @Test
  public void error_DiscardsPartiallyTransferredData() {
    // Requirements 3.3.7 & 3.3.8: Discard partial data on error, stay connected
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();

    handler.handleEvent(createServerIntentEvent(IntentCode.TRANSFER_FULL, "p1", 1, "missing"));
    handler.handleEvent(createPutObjectEvent("flag", "f1", 1));
    handler.handleEvent(createPutObjectEvent("flag", "f2", 1));

    // Error occurs - partial data should be discarded
    FDv2Event errorEvt = createErrorEvent("p1", "Something went wrong");
    FDv2ProtocolHandler.IFDv2ProtocolAction errorAction = handler.handleEvent(errorEvt);

    assertTrue(errorAction instanceof FDv2ProtocolHandler.FDv2ActionError);
    FDv2ProtocolHandler.FDv2ActionError errorActionTyped = (FDv2ProtocolHandler.FDv2ActionError) errorAction;
    assertEquals("p1", errorActionTyped.getId());
    assertEquals("Something went wrong", errorActionTyped.getReason());

    // Server recovers and resends
    handler.handleEvent(createServerIntentEvent(IntentCode.TRANSFER_FULL, "p1", 1, "retry"));
    handler.handleEvent(createPutObjectEvent("flag", "f3", 1));
    FDv2ProtocolHandler.IFDv2ProtocolAction action = handler.handleEvent(createPayloadTransferredEvent("(p:p1:1)", 1));

    // Should only have f3, not f1 or f2
    FDv2ProtocolHandler.FDv2ActionChangeset changesetAction = (FDv2ProtocolHandler.FDv2ActionChangeset) action;
    assertEquals(1, changesetAction.getChangeset().getChanges().size());
    assertEquals("f3", changesetAction.getChangeset().getChanges().get(0).getKey());
  }

  /**
   * Tests that error maintains the current state (Full vs. Changes) after clearing partial data.
   */
  @Test
  public void error_MaintainsCurrentState() {
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();

    // Start with an intent to transfer changes
    handler.handleEvent(createServerIntentEvent(IntentCode.TRANSFER_CHANGES, "p1", 1, "stale"));
    handler.handleEvent(createPutObjectEvent("flag", "f1", 1));

    // Error occurs
    handler.handleEvent(createErrorEvent("p1", "error"));

    // Continue receiving changes (no new server-intent)
    handler.handleEvent(createPutObjectEvent("flag", "f2", 1));
    FDv2ProtocolHandler.IFDv2ProtocolAction action = handler.handleEvent(createPayloadTransferredEvent("(p:p1:1)", 1));

    FDv2ProtocolHandler.FDv2ActionChangeset changesetAction = (FDv2ProtocolHandler.FDv2ActionChangeset) action;
    // Should still be Partial (the state is maintained).
    assertEquals(FDv2ChangeSet.FDv2ChangeSetType.PARTIAL, changesetAction.getChangeset().getType());
    assertEquals(1, changesetAction.getChangeset().getChanges().size());
    assertEquals("f2", changesetAction.getChangeset().getChanges().get(0).getKey());
  }

  // Requirement 3.3.5: Goodbye Handling

  /**
   * Requirement 3.3.5: SDK must log a message at the info level when a goodbye event is encountered.
   * The message must include the reason.
   */
  @Test
  public void goodbye_ReturnsGoodbyeActionWithReason() {
    // Requirement 3.3.5: Log goodbye with reason
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();
    FDv2Event goodbyeEvt = createGoodbyeEvent("Server is shutting down");

    FDv2ProtocolHandler.IFDv2ProtocolAction action = handler.handleEvent(goodbyeEvt);

    assertTrue(action instanceof FDv2ProtocolHandler.FDv2ActionGoodbye);
    FDv2ProtocolHandler.FDv2ActionGoodbye goodbyeAction = (FDv2ProtocolHandler.FDv2ActionGoodbye) action;
    assertEquals("Server is shutting down", goodbyeAction.getReason());
  }

  // Requirement 3.3.9: Heartbeat Handling

  /**
   * Requirement 3.3.9: SDK must silently handle/ignore heartbeat events.
   */
  @Test
  public void heartbeat_IsSilentlyIgnored() {
    // Requirement 3.3.9: Silently ignore heartbeat events
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();
    FDv2Event heartbeatEvt = createHeartbeatEvent();

    FDv2ProtocolHandler.IFDv2ProtocolAction action = handler.handleEvent(heartbeatEvt);

    assertTrue(action instanceof FDv2ProtocolHandler.FDv2ActionNone);
  }

  // Requirement 3.4.2: Multiple Payloads Handling

  /**
   * Requirement 3.4.2: SDK must ignore all but the first payload of the server-intent event
   * and must not crash/error when receiving messages that contain multiple payloads.
   */
  @Test
  public void serverIntent_WithMultiplePayloads_UsesOnlyFirstPayload() {
    // Requirement 3.4.2: Ignore all but the first payload
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();

    List<ServerIntent.ServerIntentPayload> payloads = new ArrayList<>();
    payloads.add(new ServerIntent.ServerIntentPayload("payload-1", 10, IntentCode.TRANSFER_CHANGES, "stale"));
    payloads.add(new ServerIntent.ServerIntentPayload("payload-2", 20, IntentCode.NONE, "up-to-date"));
    ServerIntent intent = new ServerIntent(payloads);
    String json = gsonInstance().toJson(intent);
    JsonElement data = gsonInstance().fromJson(json, JsonElement.class);
    FDv2Event evt = new FDv2Event(FDv2EventTypes.SERVER_INTENT, data);

    FDv2ProtocolHandler.IFDv2ProtocolAction action = handler.handleEvent(evt);

    // Should return None because the first payload is TransferChanges (not None)
    assertTrue(action instanceof FDv2ProtocolHandler.FDv2ActionNone);

    // Verify we're in Changes state by sending changes
    handler.handleEvent(createPutObjectEvent("flag", "f1", 1));
    FDv2ProtocolHandler.FDv2ActionChangeset changesetAction =
        (FDv2ProtocolHandler.FDv2ActionChangeset) handler.handleEvent(createPayloadTransferredEvent("(p:p1:1)", 1));
    assertEquals(FDv2ChangeSet.FDv2ChangeSetType.PARTIAL, changesetAction.getChangeset().getType());
  }

  // Error Type Handling

  /**
   * Tests that unknown event types are handled gracefully with UnknownEvent error type.
   */
  @Test
  public void unknownEventType_ReturnsUnknownEventError() {
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();
    JsonElement data = gsonInstance().fromJson("{}", JsonElement.class);
    FDv2Event unknownEvt = new FDv2Event("unknown-event-type", data);

    FDv2ProtocolHandler.IFDv2ProtocolAction action = handler.handleEvent(unknownEvt);

    assertTrue(action instanceof FDv2ProtocolHandler.FDv2ActionInternalError);
    FDv2ProtocolHandler.FDv2ActionInternalError internalError = (FDv2ProtocolHandler.FDv2ActionInternalError) action;
    assertEquals(FDv2ProtocolHandler.FDv2ProtocolErrorType.UNKNOWN_EVENT, internalError.getErrorType());
    assertTrue(internalError.getMessage().contains("unknown-event-type"));
  }

  /**
   * Tests that server-intent with empty payload list returns MissingPayload error type.
   */
  @Test
  public void serverIntent_WithEmptyPayloadList_ReturnsMissingPayloadError() {
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();

    ServerIntent intent = new ServerIntent(Collections.emptyList());
    String json = gsonInstance().toJson(intent);
    JsonElement data = gsonInstance().fromJson(json, JsonElement.class);
    FDv2Event evt = new FDv2Event(FDv2EventTypes.SERVER_INTENT, data);

    FDv2ProtocolHandler.IFDv2ProtocolAction action = handler.handleEvent(evt);

    assertTrue(action instanceof FDv2ProtocolHandler.FDv2ActionInternalError);
    FDv2ProtocolHandler.FDv2ActionInternalError internalError = (FDv2ProtocolHandler.FDv2ActionInternalError) action;
    assertEquals(FDv2ProtocolHandler.FDv2ProtocolErrorType.MISSING_PAYLOAD, internalError.getErrorType());
    assertTrue(internalError.getMessage().contains("No payload present"));
  }

  /**
   * Tests that payload-transferred without server-intent returns ProtocolError error type.
   */
  @Test
  public void payloadTransferred_WithoutServerIntent_ReturnsProtocolErrorType() {
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();

    FDv2Event transferredEvt = createPayloadTransferredEvent("(p:payload-123:52)", 52);
    FDv2ProtocolHandler.IFDv2ProtocolAction action = handler.handleEvent(transferredEvt);

    assertTrue(action instanceof FDv2ProtocolHandler.FDv2ActionInternalError);
    FDv2ProtocolHandler.FDv2ActionInternalError internalError = (FDv2ProtocolHandler.FDv2ActionInternalError) action;
    assertEquals(FDv2ProtocolHandler.FDv2ProtocolErrorType.PROTOCOL_ERROR, internalError.getErrorType());
    assertTrue(internalError.getMessage().contains("without an intent"));
  }

  // State Transitions

  /**
   * Tests that after payload-transferred, the handler transitions to Changes state
   * to receive subsequent incremental updates.
   */
  @Test
  public void payloadTransferred_TransitionsToChangesState() {
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();

    // Start with full transfer
    handler.handleEvent(createServerIntentEvent(IntentCode.TRANSFER_FULL, "p1", 1, "missing"));
    handler.handleEvent(createPutObjectEvent("flag", "f1", 1));
    FDv2ProtocolHandler.IFDv2ProtocolAction action1 = handler.handleEvent(createPayloadTransferredEvent("(p:p1:1)", 1));

    FDv2ChangeSet changeset1 = ((FDv2ProtocolHandler.FDv2ActionChangeset) action1).getChangeset();
    assertEquals(FDv2ChangeSet.FDv2ChangeSetType.FULL, changeset1.getType());

    // Now send more changes without new server-intent - should be Partial
    handler.handleEvent(createPutObjectEvent("flag", "f2", 2));
    FDv2ProtocolHandler.IFDv2ProtocolAction action2 = handler.handleEvent(createPayloadTransferredEvent("(p:p1:2)", 2));

    FDv2ChangeSet changeset2 = ((FDv2ProtocolHandler.FDv2ActionChangeset) action2).getChangeset();
    assertEquals(FDv2ChangeSet.FDv2ChangeSetType.PARTIAL, changeset2.getType());
  }

  /**
   * Tests that IntentCode.None properly sets the state to Changes.
   */
  @Test
  public void serverIntent_WithIntentCodeNone_TransitionsToChangesState() {
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();

    // Receive intent with None
    handler.handleEvent(createServerIntentEvent(IntentCode.NONE, "p1", 1, "up-to-date"));

    // Now send incremental changes
    handler.handleEvent(createPutObjectEvent("flag", "f1", 2));
    FDv2ProtocolHandler.IFDv2ProtocolAction action = handler.handleEvent(createPayloadTransferredEvent("(p:p1:2)", 2));

    FDv2ChangeSet changeset = ((FDv2ProtocolHandler.FDv2ActionChangeset) action).getChangeset();
    assertEquals(FDv2ChangeSet.FDv2ChangeSetType.PARTIAL, changeset.getType());
  }

  // Put and Delete Operations

  /**
   * Tests that put-object events correctly accumulate with all required fields.
   * Section 3.2: put-object contains payload objects that should be accepted with upsert semantics.
   */
  @Test
  public void putObject_AccumulatesWithAllFields() {
    // Section 3.2: put-object with upsert semantics
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();

    handler.handleEvent(createServerIntentEvent(IntentCode.TRANSFER_FULL, "p1", 1, "missing"));

    String flagData = "{\"key\":\"test-flag\",\"on\":true, \"version\": 314}";
    FDv2Event putEvt = createPutObjectEvent("flag", "test-flag", 42, flagData);
    handler.handleEvent(putEvt);

    FDv2ProtocolHandler.IFDv2ProtocolAction action = handler.handleEvent(createPayloadTransferredEvent("(p:p1:1)", 1));

    FDv2ChangeSet changeset = ((FDv2ProtocolHandler.FDv2ActionChangeset) action).getChangeset();
    assertEquals(1, changeset.getChanges().size());
    assertEquals(FDv2ChangeSet.FDv2ChangeType.PUT, changeset.getChanges().get(0).getType());
    assertEquals("flag", changeset.getChanges().get(0).getKind());
    assertEquals("test-flag", changeset.getChanges().get(0).getKey());
    assertEquals(42, changeset.getChanges().get(0).getVersion());
    assertNotNull(changeset.getChanges().get(0).getObject());

    // Verify we can access the stored JSON element
    JsonElement flagElement = changeset.getChanges().get(0).getObject();
    assertEquals("test-flag", flagElement.getAsJsonObject().get("key").getAsString());
    assertEquals(314, flagElement.getAsJsonObject().get("version").getAsInt());
    assertTrue(flagElement.getAsJsonObject().get("on").getAsBoolean());
  }

  /**
   * Tests that delete-object events correctly accumulate.
   * Section 3.3: delete-object contains payload objects that should be deleted.
   */
  @Test
  public void deleteObject_AccumulatesWithAllFields() {
    // Section 3.3: delete-object
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();

    handler.handleEvent(createServerIntentEvent(IntentCode.TRANSFER_CHANGES, "p1", 1, "stale"));

    FDv2Event deleteEvt = createDeleteObjectEvent("segment", "old-segment", 99);
    handler.handleEvent(deleteEvt);

    FDv2ProtocolHandler.IFDv2ProtocolAction action = handler.handleEvent(createPayloadTransferredEvent("(p:p1:1)", 1));

    FDv2ChangeSet changeset = ((FDv2ProtocolHandler.FDv2ActionChangeset) action).getChangeset();
    assertEquals(1, changeset.getChanges().size());
    assertEquals(FDv2ChangeSet.FDv2ChangeType.DELETE, changeset.getChanges().get(0).getType());
    assertEquals("segment", changeset.getChanges().get(0).getKind());
    assertEquals("old-segment", changeset.getChanges().get(0).getKey());
    assertEquals(99, changeset.getChanges().get(0).getVersion());
    assertNull(changeset.getChanges().get(0).getObject());
  }

  /**
   * Tests that put and delete operations can be mixed in a single changeset.
   */
  @Test
  public void putAndDelete_CanBeMixedInSameChangeset() {
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();

    handler.handleEvent(createServerIntentEvent(IntentCode.TRANSFER_CHANGES, "p1", 1, "stale"));

    handler.handleEvent(createPutObjectEvent("flag", "f1", 1));
    handler.handleEvent(createDeleteObjectEvent("flag", "f2", 1));
    handler.handleEvent(createPutObjectEvent("segment", "s1", 1));
    handler.handleEvent(createDeleteObjectEvent("segment", "s2", 1));

    FDv2ProtocolHandler.IFDv2ProtocolAction action = handler.handleEvent(createPayloadTransferredEvent("(p:p1:1)", 1));

    FDv2ChangeSet changeset = ((FDv2ProtocolHandler.FDv2ActionChangeset) action).getChangeset();
    assertEquals(4, changeset.getChanges().size());
    assertEquals(FDv2ChangeSet.FDv2ChangeType.PUT, changeset.getChanges().get(0).getType());
    assertEquals("f1", changeset.getChanges().get(0).getKey());
    assertEquals(FDv2ChangeSet.FDv2ChangeType.DELETE, changeset.getChanges().get(1).getType());
    assertEquals("f2", changeset.getChanges().get(1).getKey());
    assertEquals(FDv2ChangeSet.FDv2ChangeType.PUT, changeset.getChanges().get(2).getType());
    assertEquals("s1", changeset.getChanges().get(2).getKey());
    assertEquals(FDv2ChangeSet.FDv2ChangeType.DELETE, changeset.getChanges().get(3).getType());
    assertEquals("s2", changeset.getChanges().get(3).getKey());
  }

  // Multiple Transfer Cycles

  /**
   * Tests that the handler can process multiple complete transfer cycles.
   * Simulates a streaming connection receiving multiple payload updates over time.
   */
  @Test
  public void multipleTransferCycles_AreHandledCorrectly() {
    // Section 2.1.1: "some time later" - multiple transfers
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();

    // First full transfer
    handler.handleEvent(createServerIntentEvent(IntentCode.TRANSFER_FULL, "p1", 52, "missing"));
    handler.handleEvent(createPutObjectEvent("flag", "f1", 1));
    handler.handleEvent(createPutObjectEvent("flag", "f2", 1));
    FDv2ProtocolHandler.IFDv2ProtocolAction action1 = handler.handleEvent(createPayloadTransferredEvent("(p:p1:52)", 52));

    FDv2ChangeSet changeset1 = ((FDv2ProtocolHandler.FDv2ActionChangeset) action1).getChangeset();
    assertEquals(FDv2ChangeSet.FDv2ChangeSetType.FULL, changeset1.getType());
    assertEquals(2, changeset1.getChanges().size());

    // Second incremental transfer (some time later)
    handler.handleEvent(createPutObjectEvent("flag", "f1", 2));
    handler.handleEvent(createDeleteObjectEvent("flag", "f2", 2));
    FDv2ProtocolHandler.IFDv2ProtocolAction action2 = handler.handleEvent(createPayloadTransferredEvent("(p:p1:53)", 53));

    FDv2ChangeSet changeset2 = ((FDv2ProtocolHandler.FDv2ActionChangeset) action2).getChangeset();
    assertEquals(FDv2ChangeSet.FDv2ChangeSetType.PARTIAL, changeset2.getType());
    assertEquals(2, changeset2.getChanges().size());

    // Third incremental transfer
    handler.handleEvent(createPutObjectEvent("flag", "f3", 3));
    FDv2ProtocolHandler.IFDv2ProtocolAction action3 = handler.handleEvent(createPayloadTransferredEvent("(p:p1:54)", 54));

    FDv2ChangeSet changeset3 = ((FDv2ProtocolHandler.FDv2ActionChangeset) action3).getChangeset();
    assertEquals(FDv2ChangeSet.FDv2ChangeSetType.PARTIAL, changeset3.getType());
    assertEquals(1, changeset3.getChanges().size());
  }

  /**
   * Tests that receiving a new server-intent during an ongoing transfer properly resets state.
   * Per spec: "The SDK may receive multiple server-intent messages with xfer-full within one connection's lifespan."
   */
  @Test
  public void newServerIntent_DuringTransfer_ResetsState() {
    // Requirement 3.3.1: SDK may receive multiple server-intent messages
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();

    // Start first transfer
    handler.handleEvent(createServerIntentEvent(IntentCode.TRANSFER_FULL, "p1", 1, "missing"));
    handler.handleEvent(createPutObjectEvent("flag", "f1", 1));

    // Receive new server-intent before payload-transferred (e.g., server restarted)
    handler.handleEvent(createServerIntentEvent(IntentCode.TRANSFER_FULL, "p1", 2, "reset"));
    handler.handleEvent(createPutObjectEvent("flag", "f2", 2));
    FDv2ProtocolHandler.IFDv2ProtocolAction action = handler.handleEvent(createPayloadTransferredEvent("(p:p1:2)", 2));

    FDv2ChangeSet changeset = ((FDv2ProtocolHandler.FDv2ActionChangeset) action).getChangeset();
    // Should only have f2, the first transfer was abandoned
    assertEquals(1, changeset.getChanges().size());
    assertEquals("f2", changeset.getChanges().get(0).getKey());
  }

  // Empty Payloads and Edge Cases

  /**
   * Tests handling of a transfer with no objects.
   */
  @Test
  public void transfer_WithNoObjects_EmitsEmptyChangeset() {
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();

    handler.handleEvent(createServerIntentEvent(IntentCode.TRANSFER_FULL, "p1", 1, "missing"));
    // No put or delete events
    FDv2ProtocolHandler.IFDv2ProtocolAction action = handler.handleEvent(createPayloadTransferredEvent("(p:p1:1)", 1));

    FDv2ChangeSet changeset = ((FDv2ProtocolHandler.FDv2ActionChangeset) action).getChangeset();
    assertEquals(FDv2ChangeSet.FDv2ChangeSetType.FULL, changeset.getType());
    assertTrue(changeset.getChanges().isEmpty());
  }

  // Selector Verification

  /**
   * Tests that the selector is properly populated from payload-transferred event.
   */
  @Test
  public void payloadTransferred_PopulatesSelector() {
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();

    handler.handleEvent(createServerIntentEvent(IntentCode.TRANSFER_FULL, "test-payload-id", 42, "missing"));
    handler.handleEvent(createPutObjectEvent("flag", "f1", 1));
    FDv2ProtocolHandler.IFDv2ProtocolAction action = handler.handleEvent(createPayloadTransferredEvent("(p:test-payload-id:42)", 42));

    FDv2ChangeSet changeset = ((FDv2ProtocolHandler.FDv2ActionChangeset) action).getChangeset();
    assertFalse(changeset.getSelector().isEmpty());
    assertEquals("(p:test-payload-id:42)", changeset.getSelector().getState());
    assertEquals(42, changeset.getSelector().getVersion());
  }

  /**
   * Tests that ChangeSet.None has an empty selector.
   */
  @Test
  public void changeSetNone_HasEmptySelector() {
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();

    FDv2ProtocolHandler.IFDv2ProtocolAction action = handler.handleEvent(createServerIntentEvent(IntentCode.NONE, "p1", 1, "up-to-date"));

    FDv2ChangeSet changeset = ((FDv2ProtocolHandler.FDv2ActionChangeset) action).getChangeset();
    assertTrue(changeset.getSelector().isEmpty());
  }

  // FDv2Event Type Validation

  /**
   * Tests that AsServerIntent throws FDv2EventTypeMismatchException when called on a non-server-intent event.
   */
  @Test
  public void asServerIntent_WithWrongEventType_ThrowsFDv2EventTypeMismatchException() throws Exception {
    FDv2Event evt = createPutObjectEvent("flag", "f1", 1);
    try {
      evt.asServerIntent();
      fail("Expected FDv2EventTypeMismatchException");
    } catch (FDv2EventTypeMismatchException ex) {
      assertEquals(FDv2EventTypes.PUT_OBJECT, ex.getActualEventType());
      assertEquals(FDv2EventTypes.SERVER_INTENT, ex.getExpectedEventType());
    }
  }

  /**
   * Tests that AsPutObject throws FDv2EventTypeMismatchException when called on a non-put-object event.
   */
  @Test
  public void asPutObject_WithWrongEventType_ThrowsFDv2EventTypeMismatchException() throws Exception {
    FDv2Event evt = createServerIntentEvent(IntentCode.NONE);
    try {
      evt.asPutObject();
      fail("Expected FDv2EventTypeMismatchException");
    } catch (FDv2EventTypeMismatchException ex) {
      assertEquals(FDv2EventTypes.SERVER_INTENT, ex.getActualEventType());
      assertEquals(FDv2EventTypes.PUT_OBJECT, ex.getExpectedEventType());
    }
  }

  /**
   * Tests that AsDeleteObject throws FDv2EventTypeMismatchException when called on a non-delete-object event.
   */
  @Test
  public void asDeleteObject_WithWrongEventType_ThrowsFDv2EventTypeMismatchException() throws Exception {
    FDv2Event evt = createServerIntentEvent(IntentCode.NONE);
    try {
      evt.asDeleteObject();
      fail("Expected FDv2EventTypeMismatchException");
    } catch (FDv2EventTypeMismatchException ex) {
      assertEquals(FDv2EventTypes.SERVER_INTENT, ex.getActualEventType());
      assertEquals(FDv2EventTypes.DELETE_OBJECT, ex.getExpectedEventType());
    }
  }

  /**
   * Tests that AsPayloadTransferred throws FDv2EventTypeMismatchException when called on a non-payload-transferred event.
   */
  @Test
  public void asPayloadTransferred_WithWrongEventType_ThrowsFDv2EventTypeMismatchException() throws Exception {
    FDv2Event evt = createServerIntentEvent(IntentCode.NONE);
    try {
      evt.asPayloadTransferred();
      fail("Expected FDv2EventTypeMismatchException");
    } catch (FDv2EventTypeMismatchException ex) {
      assertEquals(FDv2EventTypes.SERVER_INTENT, ex.getActualEventType());
      assertEquals(FDv2EventTypes.PAYLOAD_TRANSFERRED, ex.getExpectedEventType());
    }
  }

  /**
   * Tests that AsError throws FDv2EventTypeMismatchException when called on a non-error event.
   */
  @Test
  public void asError_WithWrongEventType_ThrowsFDv2EventTypeMismatchException() throws Exception {
    FDv2Event evt = createServerIntentEvent(IntentCode.NONE);
    try {
      evt.asError();
      fail("Expected FDv2EventTypeMismatchException");
    } catch (FDv2EventTypeMismatchException ex) {
      assertEquals(FDv2EventTypes.SERVER_INTENT, ex.getActualEventType());
      assertEquals(FDv2EventTypes.ERROR, ex.getExpectedEventType());
    }
  }

  /**
   * Tests that AsGoodbye throws FDv2EventTypeMismatchException when called on a non-goodbye event.
   */
  @Test
  public void asGoodbye_WithWrongEventType_ThrowsFDv2EventTypeMismatchException() throws Exception {
    FDv2Event evt = createServerIntentEvent(IntentCode.NONE);
    try {
      evt.asGoodbye();
      fail("Expected FDv2EventTypeMismatchException");
    } catch (FDv2EventTypeMismatchException ex) {
      assertEquals(FDv2EventTypes.SERVER_INTENT, ex.getActualEventType());
      assertEquals(FDv2EventTypes.GOODBYE, ex.getExpectedEventType());
    }
  }

  // JSON Deserialization Error Handling

  /**
   * Tests that HandleEvent returns JsonError when event data is malformed JSON.
   */
  @Test
  public void handleEvent_WithMalformedJson_ReturnsJsonError() {
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();

    // Create an event with invalid JSON data for server-intent
    JsonElement badData = gsonInstance().fromJson("{\"invalid\":\"data\"}", JsonElement.class);
    FDv2Event evt = new FDv2Event(FDv2EventTypes.SERVER_INTENT, badData);

    FDv2ProtocolHandler.IFDv2ProtocolAction action = handler.handleEvent(evt);

    assertTrue(action instanceof FDv2ProtocolHandler.FDv2ActionInternalError);
    FDv2ProtocolHandler.FDv2ActionInternalError internalError = (FDv2ProtocolHandler.FDv2ActionInternalError) action;
    assertEquals(FDv2ProtocolHandler.FDv2ProtocolErrorType.JSON_ERROR, internalError.getErrorType());
    assertTrue(internalError.getMessage().contains("Failed to deserialize"));
    assertTrue(internalError.getMessage().contains(FDv2EventTypes.SERVER_INTENT));
  }

  /**
   * Tests that HandleEvent returns JsonError when put-object data is malformed.
   */
  @Test
  public void handleEvent_WithMalformedPutObject_ReturnsJsonError() {
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();

    // First set up the state with a valid server-intent
    handler.handleEvent(createServerIntentEvent(IntentCode.TRANSFER_FULL, "p1", 1, "missing"));

    // Now send a malformed put-object
    JsonElement badData = gsonInstance().fromJson("{\"missing\":\"required fields\"}", JsonElement.class);
    FDv2Event evt = new FDv2Event(FDv2EventTypes.PUT_OBJECT, badData);

    FDv2ProtocolHandler.IFDv2ProtocolAction action = handler.handleEvent(evt);

    assertTrue(action instanceof FDv2ProtocolHandler.FDv2ActionInternalError);
    FDv2ProtocolHandler.FDv2ActionInternalError internalError = (FDv2ProtocolHandler.FDv2ActionInternalError) action;
    assertEquals(FDv2ProtocolHandler.FDv2ProtocolErrorType.JSON_ERROR, internalError.getErrorType());
    assertTrue(internalError.getMessage().contains("Failed to deserialize"));
    assertTrue(internalError.getMessage().contains(FDv2EventTypes.PUT_OBJECT));
  }

  /**
   * Tests that HandleEvent returns JsonError when payload-transferred data is malformed.
   */
  @Test
  public void handleEvent_WithMalformedPayloadTransferred_ReturnsJsonError() {
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();

    // First set up the state with a valid server-intent
    handler.handleEvent(createServerIntentEvent(IntentCode.TRANSFER_FULL, "p1", 1, "missing"));

    // Now send a malformed payload-transferred
    JsonElement badData = gsonInstance().fromJson("{\"incomplete\":\"data\"}", JsonElement.class);
    FDv2Event evt = new FDv2Event(FDv2EventTypes.PAYLOAD_TRANSFERRED, badData);

    FDv2ProtocolHandler.IFDv2ProtocolAction action = handler.handleEvent(evt);

    assertTrue(action instanceof FDv2ProtocolHandler.FDv2ActionInternalError);
    FDv2ProtocolHandler.FDv2ActionInternalError internalError = (FDv2ProtocolHandler.FDv2ActionInternalError) action;
    assertEquals(FDv2ProtocolHandler.FDv2ProtocolErrorType.JSON_ERROR, internalError.getErrorType());
    assertTrue(internalError.getMessage().contains("Failed to deserialize"));
    assertTrue(internalError.getMessage().contains(FDv2EventTypes.PAYLOAD_TRANSFERRED));
  }

  // Reset Method

  /**
   * Tests that Reset clears accumulated changes and resets state to Inactive.
   */
  @Test
  public void reset_ClearsAccumulatedChanges() {
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();

    // Set up state with accumulated changes
    handler.handleEvent(createServerIntentEvent(IntentCode.TRANSFER_FULL, "p1", 1, "missing"));
    handler.handleEvent(createPutObjectEvent("flag", "f1", 1));
    handler.handleEvent(createPutObjectEvent("flag", "f2", 1));

    // Reset the handler
    handler.reset();

    // Attempting to send payload-transferred without new server-intent should return protocol error
    // because reset puts the handler back to Inactive state
    FDv2Event transferredEvt = createPayloadTransferredEvent("(p:p1:1)", 1);
    FDv2ProtocolHandler.IFDv2ProtocolAction action = handler.handleEvent(transferredEvt);

    assertTrue(action instanceof FDv2ProtocolHandler.FDv2ActionInternalError);
    FDv2ProtocolHandler.FDv2ActionInternalError internalError = (FDv2ProtocolHandler.FDv2ActionInternalError) action;
    assertEquals(FDv2ProtocolHandler.FDv2ProtocolErrorType.PROTOCOL_ERROR, internalError.getErrorType());
    assertTrue(internalError.getMessage().contains("without an intent"));
  }

  /**
   * Tests that Reset allows starting a new transfer cycle.
   */
  @Test
  public void reset_AllowsNewTransferCycle() {
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();

    // First transfer cycle
    handler.handleEvent(createServerIntentEvent(IntentCode.TRANSFER_FULL, "p1", 1, "missing"));
    handler.handleEvent(createPutObjectEvent("flag", "f1", 1));

    // Reset
    handler.reset();

    // New transfer cycle should work
    handler.handleEvent(createServerIntentEvent(IntentCode.TRANSFER_FULL, "p2", 2, "missing"));
    handler.handleEvent(createPutObjectEvent("flag", "f2", 2));
    FDv2ProtocolHandler.IFDv2ProtocolAction action = handler.handleEvent(createPayloadTransferredEvent("(p:p2:2)", 2));

    FDv2ProtocolHandler.FDv2ActionChangeset changesetAction = (FDv2ProtocolHandler.FDv2ActionChangeset) action;
    assertEquals(FDv2ChangeSet.FDv2ChangeSetType.FULL, changesetAction.getChangeset().getType());
    // Should only have f2, not f1 (which was cleared by reset)
    assertEquals(1, changesetAction.getChangeset().getChanges().size());
    assertEquals("f2", changesetAction.getChangeset().getChanges().get(0).getKey());
  }

  /**
   * Tests that Reset during an ongoing Full transfer properly clears partial data.
   */
  @Test
  public void reset_DuringFullTransfer_ClearsPartialData() {
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();

    handler.handleEvent(createServerIntentEvent(IntentCode.TRANSFER_FULL, "p1", 1, "missing"));
    handler.handleEvent(createPutObjectEvent("flag", "f1", 1));
    handler.handleEvent(createPutObjectEvent("flag", "f2", 1));
    handler.handleEvent(createPutObjectEvent("flag", "f3", 1));

    // Reset before payload-transferred
    handler.reset();

    // Start new transfer
    handler.handleEvent(createServerIntentEvent(IntentCode.TRANSFER_CHANGES, "p2", 2, "stale"));
    handler.handleEvent(createPutObjectEvent("flag", "f4", 2));
    FDv2ProtocolHandler.IFDv2ProtocolAction action = handler.handleEvent(createPayloadTransferredEvent("(p:p2:2)", 2));

    FDv2ProtocolHandler.FDv2ActionChangeset changesetAction = (FDv2ProtocolHandler.FDv2ActionChangeset) action;
    assertEquals(FDv2ChangeSet.FDv2ChangeSetType.PARTIAL, changesetAction.getChangeset().getType());
    assertEquals(1, changesetAction.getChangeset().getChanges().size());
    assertEquals("f4", changesetAction.getChangeset().getChanges().get(0).getKey());
  }

  /**
   * Tests that Reset during an ongoing Changes transfer properly clears partial data.
   */
  @Test
  public void reset_DuringChangesTransfer_ClearsPartialData() {
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();

    handler.handleEvent(createServerIntentEvent(IntentCode.TRANSFER_CHANGES, "p1", 1, "stale"));
    handler.handleEvent(createPutObjectEvent("flag", "f1", 1));
    handler.handleEvent(createDeleteObjectEvent("flag", "f2", 1));

    // Reset before payload-transferred
    handler.reset();

    // Start new transfer
    handler.handleEvent(createServerIntentEvent(IntentCode.TRANSFER_FULL, "p2", 2, "missing"));
    handler.handleEvent(createPutObjectEvent("flag", "f3", 2));
    FDv2ProtocolHandler.IFDv2ProtocolAction action = handler.handleEvent(createPayloadTransferredEvent("(p:p2:2)", 2));

    FDv2ProtocolHandler.FDv2ActionChangeset changesetAction = (FDv2ProtocolHandler.FDv2ActionChangeset) action;
    assertEquals(FDv2ChangeSet.FDv2ChangeSetType.FULL, changesetAction.getChangeset().getType());
    assertEquals(1, changesetAction.getChangeset().getChanges().size());
    assertEquals("f3", changesetAction.getChangeset().getChanges().get(0).getKey());
  }

  /**
   * Tests that Reset can be called multiple times safely.
   */
  @Test
  public void reset_CanBeCalledMultipleTimes() {
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();

    // Reset on fresh handler
    handler.reset();

    // Set up state
    handler.handleEvent(createServerIntentEvent(IntentCode.TRANSFER_FULL, "p1", 1, "missing"));
    handler.handleEvent(createPutObjectEvent("flag", "f1", 1));

    // Reset again
    handler.reset();

    // Reset yet again
    handler.reset();

    // Should still work normally
    handler.handleEvent(createServerIntentEvent(IntentCode.NONE, "p1", 1, "up-to-date"));
    FDv2ProtocolHandler.IFDv2ProtocolAction action = handler.handleEvent(createServerIntentEvent(IntentCode.NONE, "p1", 1, "up-to-date"));

    assertTrue(action instanceof FDv2ProtocolHandler.FDv2ActionChangeset);
    FDv2ProtocolHandler.FDv2ActionChangeset changesetAction = (FDv2ProtocolHandler.FDv2ActionChangeset) action;
    assertEquals(FDv2ChangeSet.FDv2ChangeSetType.NONE, changesetAction.getChangeset().getType());
  }

  /**
   * Tests that Reset after a completed transfer works correctly.
   * Simulates connection reset after successful data transfer.
   */
  @Test
  public void reset_AfterCompletedTransfer_WorksCorrectly() {
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();

    // Complete a full transfer
    handler.handleEvent(createServerIntentEvent(IntentCode.TRANSFER_FULL, "p1", 1, "missing"));
    handler.handleEvent(createPutObjectEvent("flag", "f1", 1));
    FDv2ProtocolHandler.IFDv2ProtocolAction action1 = handler.handleEvent(createPayloadTransferredEvent("(p:p1:1)", 1));

    assertTrue(action1 instanceof FDv2ProtocolHandler.FDv2ActionChangeset);

    // Reset (simulating connection reset)
    handler.reset();

    // Start new transfer after reset
    handler.handleEvent(createServerIntentEvent(IntentCode.TRANSFER_FULL, "p2", 2, "missing"));
    handler.handleEvent(createPutObjectEvent("flag", "f2", 2));
    FDv2ProtocolHandler.IFDv2ProtocolAction action2 = handler.handleEvent(createPayloadTransferredEvent("(p:p2:2)", 2));

    FDv2ProtocolHandler.FDv2ActionChangeset changesetAction = (FDv2ProtocolHandler.FDv2ActionChangeset) action2;
    assertEquals(FDv2ChangeSet.FDv2ChangeSetType.FULL, changesetAction.getChangeset().getType());
    assertEquals(1, changesetAction.getChangeset().getChanges().size());
    assertEquals("f2", changesetAction.getChangeset().getChanges().get(0).getKey());
  }

  /**
   * Tests that Reset after receiving an error properly clears state.
   */
  @Test
  public void reset_AfterError_ClearsState() {
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();

    handler.handleEvent(createServerIntentEvent(IntentCode.TRANSFER_FULL, "p1", 1, "missing"));
    handler.handleEvent(createPutObjectEvent("flag", "f1", 1));

    // Receive error
    FDv2ProtocolHandler.IFDv2ProtocolAction errorAction = handler.handleEvent(createErrorEvent("p1", "Something went wrong"));
    assertTrue(errorAction instanceof FDv2ProtocolHandler.FDv2ActionError);

    // Reset after error
    handler.reset();

    // Verify state is Inactive by attempting payload-transferred without intent
    FDv2Event transferredEvt = createPayloadTransferredEvent("(p:p1:1)", 1);
    FDv2ProtocolHandler.IFDv2ProtocolAction action = handler.handleEvent(transferredEvt);

    assertTrue(action instanceof FDv2ProtocolHandler.FDv2ActionInternalError);
    FDv2ProtocolHandler.FDv2ActionInternalError internalError = (FDv2ProtocolHandler.FDv2ActionInternalError) action;
    assertEquals(FDv2ProtocolHandler.FDv2ProtocolErrorType.PROTOCOL_ERROR, internalError.getErrorType());
  }

  /**
   * Tests that Reset properly handles the case where mixed put and delete operations were accumulated.
   */
  @Test
  public void reset_WithMixedOperations_ClearsAllChanges() {
    FDv2ProtocolHandler handler = new FDv2ProtocolHandler();

    handler.handleEvent(createServerIntentEvent(IntentCode.TRANSFER_CHANGES, "p1", 1, "stale"));
    handler.handleEvent(createPutObjectEvent("flag", "f1", 1));
    handler.handleEvent(createDeleteObjectEvent("flag", "f2", 1));
    handler.handleEvent(createPutObjectEvent("segment", "s1", 1));
    handler.handleEvent(createDeleteObjectEvent("segment", "s2", 1));

    // Reset
    handler.reset();

    // New transfer should not include any of the previous changes
    handler.handleEvent(createServerIntentEvent(IntentCode.TRANSFER_FULL, "p2", 2, "missing"));
    handler.handleEvent(createPutObjectEvent("flag", "f-new", 2));
    FDv2ProtocolHandler.IFDv2ProtocolAction action = handler.handleEvent(createPayloadTransferredEvent("(p:p2:2)", 2));

    FDv2ProtocolHandler.FDv2ActionChangeset changesetAction = (FDv2ProtocolHandler.FDv2ActionChangeset) action;
    assertEquals(1, changesetAction.getChangeset().getChanges().size());
    assertEquals("f-new", changesetAction.getChangeset().getChanges().get(0).getKey());
  }
}

