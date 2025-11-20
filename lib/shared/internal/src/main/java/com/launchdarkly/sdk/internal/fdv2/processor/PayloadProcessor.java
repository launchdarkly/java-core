package com.launchdarkly.sdk.internal.fdv2.processor;

import com.google.gson.JsonElement;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.internal.GsonHelpers;
import com.launchdarkly.sdk.internal.fdv2.protocol.DeleteObject;
import com.launchdarkly.sdk.internal.fdv2.protocol.Error;
import com.launchdarkly.sdk.internal.fdv2.protocol.Event;
import com.launchdarkly.sdk.internal.fdv2.protocol.IntentCode;
import com.launchdarkly.sdk.internal.fdv2.protocol.PayloadIntent;
import com.launchdarkly.sdk.internal.fdv2.protocol.PayloadTransferred;
import com.launchdarkly.sdk.internal.fdv2.protocol.PutObject;
import com.launchdarkly.sdk.internal.fdv2.protocol.ServerIntentData;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A FDv2 PayloadProcessor can be used to parse payloads from a sequence of FDv2 events. It will send payloads
 * to the PayloadListeners as the payloads are received. Invalid series of events may be dropped silently,
 * but the payload processor will continue to operate.
 */
public class PayloadProcessor {

    /**
     * Enumeration describing the kind of error that occurred during payload processing.
     */
    public enum ErrorKind {
        /**
         * The SDK received malformed data that could not be parsed.
         */
        INVALID_DATA,

        /**
         * An unexpected error, such as a protocol-level error from the LaunchDarkly service.
         */
        UNKNOWN
    }

    /**
     * Functional interface for receiving payloads and errors.
     */
    public interface PayloadListener {
        /**
         * Called when a payload is received.
         *
         * @param payload the payload
         */
        void onPayload(Payload payload);

        /**
         * Called when an error occurs during payload processing.
         *
         * @param errorKind the kind of error
         * @param message the error message
         */
        void onError(ErrorKind errorKind, String message);
    }

    private final LDLogger logger;
    private final List<PayloadListener> listeners;

    private String tempId;
    private boolean tempBasis;
    private List<Update> tempUpdates;

    /**
     * Creates a PayloadProcessor.
     *
     * @param logger for logging (may be null)
     */
    public PayloadProcessor(LDLogger logger) {
        this.logger = logger;
        this.listeners = new CopyOnWriteArrayList<>();
        this.tempUpdates = new ArrayList<>();
    }

    /**
     * Adds a payload listener.
     *
     * @param listener the listener to add
     */
    public void addPayloadListener(PayloadListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * Removes a payload listener.
     *
     * @param listener the listener to remove
     */
    public void removePayloadListener(PayloadListener listener) {
        listeners.remove(listener);
    }

    /**
     * Gives the PayloadProcessor a series of events that it will statefully, incrementally process.
     * This may lead to listeners being invoked as necessary.
     * <p>
     * This method is thread-safe. Multiple threads can call this method concurrently, but each
     * call will process events atomically with respect to the processor's internal state.
     *
     * @param events to be processed (can be a single element)
     */
    public synchronized void processEvents(List<Event> events) {
        if (events == null) {
            return;
        }
        for (Event event : events) {
            if (event == null || event.getEvent() == null) {
                continue;
            }
            switch (event.getEvent()) {
                case "server-intent":
                    processServerIntent(event.getData());
                    break;
                case "put-object":
                    processPutObject(event.getData());
                    break;
                case "delete-object":
                    processDeleteObject(event.getData());
                    break;
                case "payload-transferred":
                    processPayloadTransferred(event.getData());
                    break;
                case "goodbye":
                    processGoodbye(event.getData());
                    break;
                case "error":
                    processError(event.getData());
                    break;
                default:
                    // no-op, unrecognized
                    break;
            }
        }
    }

    private void processServerIntent(JsonElement data) {
        if (data == null) {
            return;
        }
        try {
            ServerIntentData serverIntentData = GsonHelpers.gsonInstance().fromJson(data, ServerIntentData.class);
            if (serverIntentData == null) {
                throw new IllegalArgumentException("Failed to deserialize server-intent: result was null");
            }
            serverIntentData.validate();
            
            // clear state in prep for handling data
            resetAll();

            // if there's no payloads, return
            List<PayloadIntent> payloads = serverIntentData.getPayloads();
            if (payloads == null || payloads.isEmpty()) {
                return;
            }
            // at the time of writing this, it was agreed upon that SDKs could assume exactly 1 element
            // in this list. In the future, a negotiation of protocol version will be required to
            // remove this assumption.
            PayloadIntent payload = payloads.get(0);
            if (payload == null) {
                return;
            }

            IntentCode intentCode = payload.getIntentCode();
            if (intentCode == null) {
                reportError(ErrorKind.INVALID_DATA, "Unable to process intent code 'null'.");
                return;
            }

            switch (intentCode) {
                case XFER_FULL:
                    tempBasis = true;
                    break;
                case XFER_CHANGES:
                    tempBasis = false;
                    break;
                case NONE:
                    tempBasis = false;
                    processIntentNone(payload);
                    break;
                default:
                    // unrecognized intent code, return
                    if (logger != null) {
                        logger.warn("Unable to process intent code '{}'.", intentCode.getValue());
                    }
                    return;
            }

            tempId = payload.getId();
        } catch (Exception e) {
            reportError(ErrorKind.INVALID_DATA, "Failed to parse server-intent: " + e.getMessage());
        }
    }

    private void processPutObject(JsonElement data) {
        if (data == null) {
            return;
        }
        try {
            PutObject putObject = GsonHelpers.gsonInstance().fromJson(data, PutObject.class);
            if (putObject == null) {
                throw new IllegalArgumentException("Failed to deserialize put-object: result was null");
            }
            putObject.validate();
            // if server intent hasn't been received yet, ignore the event
            if (tempId == null) {
                return;
            }

            Update update = new Update(
                    putObject.getKind(),
                    putObject.getKey(),
                    putObject.getVersion(),
                    putObject.getObject(),
                    null // intentionally omit deleted for this put
            );
            tempUpdates.add(update);
        } catch (Exception e) {
            reportError(ErrorKind.INVALID_DATA, "Failed to parse put-object: " + e.getMessage());
        }
    }

    private void processDeleteObject(JsonElement data) {
        if (data == null) {
            return;
        }
        try {
            DeleteObject deleteObject = GsonHelpers.gsonInstance().fromJson(data, DeleteObject.class);
            if (deleteObject == null) {
                throw new IllegalArgumentException("Failed to deserialize delete-object: result was null");
            }
            deleteObject.validate();
            // if server intent hasn't been received yet, ignore the event
            if (tempId == null) {
                return;
            }

            Update update = new Update(
                    deleteObject.getKind(),
                    deleteObject.getKey(),
                    deleteObject.getVersion(),
                    null, // intentionally omit object for this delete
                    true
            );
            tempUpdates.add(update);
        } catch (Exception e) {
            reportError(ErrorKind.INVALID_DATA, "Failed to parse delete-object: " + e.getMessage());
        }
    }

    private void processIntentNone(PayloadIntent intent) {
        // if the following properties aren't present ignore the event
        if (intent.getId() == null || intent.getTarget() == 0) {
            return;
        }

        Payload payload = new Payload(
                intent.getId(),
                intent.getTarget(),
                null, // note: state is absent here as that only appears in payload transferred events
                false, // intent none is always not a basis
                new ArrayList<>() // payload with no updates to hide the intent none concept from the consumer
        );

        for (PayloadListener listener : listeners) {
            listener.onPayload(payload);
        }
        resetAfterEmission();
    }

    private void processPayloadTransferred(JsonElement data) {
        if (data == null) {
            return;
        }
        try {
            PayloadTransferred payloadTransferred = GsonHelpers.gsonInstance().fromJson(data, PayloadTransferred.class);
            if (payloadTransferred == null) {
                throw new IllegalArgumentException("Failed to deserialize payload-transferred: result was null");
            }
            payloadTransferred.validate();
            // if server intent hasn't been received yet, we should reset
            if (tempId == null) {
                resetAll(); // a reset is best defensive action since payload transferred terminates a payload
                return;
            }

            Payload payload = new Payload(
                    tempId,
                    payloadTransferred.getVersion(),
                    payloadTransferred.getState(),
                    tempBasis,
                    new ArrayList<>(tempUpdates)
            );

            for (PayloadListener listener : listeners) {
                listener.onPayload(payload);
            }
            resetAfterEmission();
        } catch (Exception e) {
            reportError(ErrorKind.INVALID_DATA, "Failed to parse payload-transferred: " + e.getMessage());
            resetAll(); // a reset is best defensive action since payload transferred terminates a payload
        }
    }

    private void processGoodbye(JsonElement data) {
        String reason = null;
        if (data != null && data.isJsonObject()) {
            try {
                com.google.gson.JsonObject jsonObj = data.getAsJsonObject();
                if (jsonObj.has("reason")) {
                    reason = jsonObj.get("reason").getAsString();
                }
            } catch (Exception e) {
                // ignore parsing errors for goodbye reason
            }
        }
        if (logger != null) {
            logger.info("Goodbye was received with reason: {}.", reason);
        }
        resetAll();
    }

    private void processError(JsonElement data) {
        if (data == null) {
            return;
        }
        try {
            Error error = GsonHelpers.gsonInstance().fromJson(data, Error.class);
            if (error == null) {
                throw new IllegalArgumentException("Failed to deserialize error: result was null");
            }
            String payloadId = error.getId();
            String reason = error.getReason();
        
            reportError(ErrorKind.UNKNOWN, "An error was encountered receiving updates for payload " + payloadId + " with reason: " + reason + ".");
            resetAfterError();
        } catch (Exception e) {
            reportError(ErrorKind.UNKNOWN, "An error was encountered receiving updates for payload.");
            resetAfterError();
        }
    }

    /**
     * Helper method to log an error and notify all listeners.
     *
     * @param errorKind the kind of error
     * @param message the error message
     */
    private void reportError(ErrorKind errorKind, String message) {
        if (logger != null) {
            logger.warn(message);
        }
        for (PayloadListener listener : listeners) {
            listener.onError(errorKind, message);
        }
    }

    private void resetAfterEmission() {
        tempBasis = false;
        tempUpdates = new ArrayList<>();
    }

    private void resetAfterError() {
        tempUpdates = new ArrayList<>();
    }

    private void resetAll() {
        tempId = null;
        tempBasis = false;
        tempUpdates = new ArrayList<>();
    }
}
