package com.launchdarkly.sdk.internal.fdv2.sources;

import com.launchdarkly.sdk.internal.fdv2.payloads.DeleteObject;
import com.launchdarkly.sdk.internal.fdv2.payloads.Error;
import com.launchdarkly.sdk.internal.fdv2.payloads.FDv2Event;
import com.launchdarkly.sdk.internal.fdv2.payloads.Goodbye;
import com.launchdarkly.sdk.internal.fdv2.payloads.PayloadTransferred;
import com.launchdarkly.sdk.internal.fdv2.payloads.PutObject;
import com.launchdarkly.sdk.internal.fdv2.payloads.ServerIntent;
import com.launchdarkly.sdk.json.SerializationException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Implements the FDv2 protocol state machine for handling payload communication events.
 * See: FDV2PL-payload-communication specification.
 */
public final class FDv2ProtocolHandler {
  /**
   * State of the protocol handler.
   */
  private enum FDv2ProtocolState {
    /**
     * No server intent has been expressed.
     */
    INACTIVE,
    /**
     * Currently receiving incremental changes.
     */
    CHANGES,
    /**
     * Currently receiving a full transfer.
     */
    FULL
  }

  /**
   * Actions emitted by the protocol handler.
   */
  public enum FDv2ProtocolActionType {
    /**
     * Indicates that a changeset should be emitted.
     */
    CHANGESET,
    /**
     * Indicates that an error has been encountered and should be logged.
     */
    ERROR,
    /**
     * Indicates that the server intends to disconnect and the SDK should log the reason.
     */
    GOODBYE,
    /**
     * Indicates that no special action should be taken.
     */
    NONE,
    /**
     * Indicates an internal error that should be logged.
     */
    INTERNAL_ERROR
  }

  /**
   * Error categories produced by the protocol handler.
   */
  public enum FDv2ProtocolErrorType {
    /**
     * Received a protocol event which is not recognized.
     */
    UNKNOWN_EVENT,
    /**
     * Server intent was received without any payloads.
     */
    MISSING_PAYLOAD,
    /**
     * The JSON couldn't be parsed or didn't conform to the schema.
     */
    JSON_ERROR,
    /**
     * Represents an implementation defect.
     */
    IMPLEMENTATION_ERROR,
    /**
     * Represents a violation of the protocol flow.
     */
    PROTOCOL_ERROR
  }

  public interface IFDv2ProtocolAction {
    FDv2ProtocolActionType getAction();
  }

  public static final class FDv2ActionChangeset implements IFDv2ProtocolAction {
    private final FDv2ChangeSet changeset;

    public FDv2ActionChangeset(FDv2ChangeSet changeset) {
      this.changeset = Objects.requireNonNull(changeset, "changeset");
    }

    @Override
    public FDv2ProtocolActionType getAction() {
      return FDv2ProtocolActionType.CHANGESET;
    }

    public FDv2ChangeSet getChangeset() {
      return changeset;
    }
  }

  public static final class FDv2ActionError implements IFDv2ProtocolAction {
    private final String id;
    private final String reason;

    public FDv2ActionError(String id, String reason) {
      this.id = id;
      this.reason = reason;
    }

    @Override
    public FDv2ProtocolActionType getAction() {
      return FDv2ProtocolActionType.ERROR;
    }

    public String getId() {
      return id;
    }

    public String getReason() {
      return reason;
    }
  }

  public static final class FDv2ActionGoodbye implements IFDv2ProtocolAction {
    private final String reason;

    public FDv2ActionGoodbye(String reason) {
      this.reason = reason;
    }

    @Override
    public FDv2ProtocolActionType getAction() {
      return FDv2ProtocolActionType.GOODBYE;
    }

    public String getReason() {
      return reason;
    }
  }

  public static final class FDv2ActionInternalError implements IFDv2ProtocolAction {
    private final String message;
    private final FDv2ProtocolErrorType errorType;

    public FDv2ActionInternalError(String message, FDv2ProtocolErrorType errorType) {
      this.message = message;
      this.errorType = errorType;
    }

    @Override
    public FDv2ProtocolActionType getAction() {
      return FDv2ProtocolActionType.INTERNAL_ERROR;
    }

    public String getMessage() {
      return message;
    }

    public FDv2ProtocolErrorType getErrorType() {
      return errorType;
    }
  }

  public static final class FDv2ActionNone implements IFDv2ProtocolAction {
    private static final FDv2ActionNone INSTANCE = new FDv2ActionNone();

    private FDv2ActionNone() {}

    public static FDv2ActionNone getInstance() {
      return INSTANCE;
    }

    @Override
    public FDv2ProtocolActionType getAction() {
      return FDv2ProtocolActionType.NONE;
    }
  }

  private final List<FDv2ChangeSet.FDv2Change> changes = new ArrayList<>();
  private FDv2ProtocolState state = FDv2ProtocolState.INACTIVE;

  private IFDv2ProtocolAction serverIntent(ServerIntent intent) {
    List<ServerIntent.ServerIntentPayload> payloads = intent.getPayloads();
    ServerIntent.ServerIntentPayload payload = (payloads == null || payloads.isEmpty())
        ? null : payloads.get(0);
    if (payload == null) {
      return new FDv2ActionInternalError("No payload present in server-intent",
          FDv2ProtocolErrorType.MISSING_PAYLOAD);
    }

    switch (payload.getIntentCode()) {
    case NONE:
      state = FDv2ProtocolState.CHANGES;
      changes.clear();
      return new FDv2ActionChangeset(FDv2ChangeSet.NONE);
    case TRANSFER_FULL:
      state = FDv2ProtocolState.FULL;
      break;
    case TRANSFER_CHANGES:
      state = FDv2ProtocolState.CHANGES;
      break;
    default:
      return new FDv2ActionInternalError("Unhandled event code: " + payload.getIntentCode(),
          FDv2ProtocolErrorType.IMPLEMENTATION_ERROR);
    }

    changes.clear();
    return FDv2ActionNone.getInstance();
  }

  private void putObject(PutObject put) {
    changes.add(new FDv2ChangeSet.FDv2Change(
        FDv2ChangeSet.FDv2ChangeType.PUT, put.getKind(), put.getKey(), put.getVersion(), put.getObject()));
  }

  private void deleteObject(DeleteObject delete) {
    changes.add(new FDv2ChangeSet.FDv2Change(
        FDv2ChangeSet.FDv2ChangeType.DELETE, delete.getKind(), delete.getKey(), delete.getVersion(), null));
  }

  private IFDv2ProtocolAction payloadTransferred(PayloadTransferred payload) {
    FDv2ChangeSet.FDv2ChangeSetType changeSetType;
    switch (state) {
    case INACTIVE:
      return new FDv2ActionInternalError(
          "A payload transferred has been received without an intent having been established.",
          FDv2ProtocolErrorType.PROTOCOL_ERROR);
    case CHANGES:
      changeSetType = FDv2ChangeSet.FDv2ChangeSetType.PARTIAL;
      break;
    case FULL:
      changeSetType = FDv2ChangeSet.FDv2ChangeSetType.FULL;
      break;
    default:
      return new FDv2ActionInternalError("Unhandled protocol state: " + state,
          FDv2ProtocolErrorType.IMPLEMENTATION_ERROR);
    }

    FDv2ChangeSet changeset = new FDv2ChangeSet(
        changeSetType,
        new ArrayList<>(changes),
        Selector.make(payload.getVersion(), payload.getState()));
    state = FDv2ProtocolState.CHANGES;
    changes.clear();
    return new FDv2ActionChangeset(changeset);
  }

  private IFDv2ProtocolAction error(Error error) {
    changes.clear();
    return new FDv2ActionError(error.getId(), error.getReason());
  }

  private IFDv2ProtocolAction goodbye(Goodbye intent) {
    return new FDv2ActionGoodbye(intent.getReason());
  }

  /**
   * Process an FDv2 event and update the protocol state accordingly.
   *
   * @param evt the event to process
   * @return an action indicating what the caller should do in response to this event
   */
  public IFDv2ProtocolAction handleEvent(FDv2Event evt) {
    try {
      switch (evt.getEventType()) {
      case FDv2EventTypes.SERVER_INTENT:
        return serverIntent(evt.asServerIntent());
      case FDv2EventTypes.DELETE_OBJECT:
        deleteObject(evt.asDeleteObject());
        break;
      case FDv2EventTypes.PUT_OBJECT:
        putObject(evt.asPutObject());
        break;
      case FDv2EventTypes.ERROR:
        return error(evt.asError());
      case FDv2EventTypes.GOODBYE:
        return goodbye(evt.asGoodbye());
      case FDv2EventTypes.PAYLOAD_TRANSFERRED:
        return payloadTransferred(evt.asPayloadTransferred());
      case FDv2EventTypes.HEARTBEAT:
        break;
      default:
        return new FDv2ActionInternalError(
            "Received an unknown event of type " + evt.getEventType(),
            FDv2ProtocolErrorType.UNKNOWN_EVENT);
      }

      return FDv2ActionNone.getInstance();
    } catch (FDv2Event.FDv2EventTypeMismatchException ex) {
      return new FDv2ActionInternalError(
          "Event type mismatch: " + ex.getMessage(),
          FDv2ProtocolErrorType.IMPLEMENTATION_ERROR);
    } catch (SerializationException ex) {
      return new FDv2ActionInternalError(
          "Failed to deserialize " + evt.getEventType() + " event: " + ex.getMessage(),
          FDv2ProtocolErrorType.JSON_ERROR);
    }
  }

  /**
   * Get a list of event types which are handled by the protocol handler.
   *
   * @return the list of handled event types
   */
  public static List<String> getHandledEventTypes() {
    return HANDLED_EVENT_TYPES;
  }

  private static final List<String> HANDLED_EVENT_TYPES;
  static {
    List<String> types = new ArrayList<>();
    types.add(FDv2EventTypes.SERVER_INTENT);
    types.add(FDv2EventTypes.DELETE_OBJECT);
    types.add(FDv2EventTypes.PUT_OBJECT);
    types.add(FDv2EventTypes.ERROR);
    types.add(FDv2EventTypes.GOODBYE);
    types.add(FDv2EventTypes.PAYLOAD_TRANSFERRED);
    types.add(FDv2EventTypes.HEARTBEAT);
    HANDLED_EVENT_TYPES = Collections.unmodifiableList(types);
  }

  /**
   * Reset the protocol handler. This should be done whenever a connection to the source of data is reset.
   */
  public void reset() {
    changes.clear();
    state = FDv2ProtocolState.INACTIVE;
  }
}


