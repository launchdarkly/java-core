package com.launchdarkly.sdk.server;

import com.launchdarkly.eventsource.ConnectStrategy;
import com.launchdarkly.eventsource.ErrorStrategy;
import com.launchdarkly.eventsource.EventSource;
import com.launchdarkly.eventsource.FaultEvent;
import com.launchdarkly.eventsource.HttpConnectStrategy;
import com.launchdarkly.eventsource.MessageEvent;
import com.launchdarkly.eventsource.StreamClosedByCallerException;
import com.launchdarkly.eventsource.StreamEvent;
import com.launchdarkly.eventsource.StreamException;
import com.launchdarkly.eventsource.StreamHttpErrorException;
import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;
import com.launchdarkly.sdk.internal.fdv2.payloads.FDv2Event;
import com.launchdarkly.sdk.internal.fdv2.sources.FDv2ProtocolHandler;
import com.launchdarkly.sdk.internal.fdv2.sources.Selector;
import com.launchdarkly.sdk.internal.http.HttpHelpers;
import com.launchdarkly.sdk.internal.http.HttpProperties;
import com.launchdarkly.sdk.server.datasources.FDv2SourceResult;
import com.launchdarkly.sdk.server.datasources.SelectorSource;
import com.launchdarkly.sdk.server.datasources.Synchronizer;
import com.launchdarkly.sdk.server.interfaces.DataSourceStatusProvider;
import com.launchdarkly.sdk.server.subsystems.DataStoreTypes;
import com.launchdarkly.sdk.server.subsystems.SerializationException;
import com.google.gson.stream.JsonReader;
import okhttp3.Headers;
import org.jetbrains.annotations.NotNull;

import java.io.Reader;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.launchdarkly.sdk.internal.http.HttpErrors.checkIfErrorIsRecoverableAndLog;

/**
 * Implementation of FDv2 streaming synchronizer.
 * Maintains a long-running streaming connection and queues results as they arrive.
 */
class StreamingSynchronizerImpl implements Synchronizer {
    private static final Duration DEAD_CONNECTION_INTERVAL = Duration.ofSeconds(300);

    private final HttpProperties httpProperties;
    private final SelectorSource selectorSource;
    final URI streamUri;
    private final LDLogger logger;
    private final String payloadFilter;
    private final IterableAsyncQueue<FDv2SourceResult> resultQueue = new IterableAsyncQueue<>();
    private final CompletableFuture<FDv2SourceResult> shutdownFuture = new CompletableFuture<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final FDv2ProtocolHandler protocolHandler = new FDv2ProtocolHandler();
    private volatile EventSource eventSource;
    final Duration initialReconnectDelay;

    private final AtomicBoolean started = new AtomicBoolean(false);

    public StreamingSynchronizerImpl(
            HttpProperties httpProperties,
            URI baseUri,
            String requestPath,
            LDLogger logger,
            SelectorSource selectorSource,
            String payloadFilter,
            Duration initialReconnectDelaySeconds
    ) {
        this.httpProperties = httpProperties;
        this.selectorSource = selectorSource;
        this.logger = logger;
        this.payloadFilter = payloadFilter;
        this.streamUri = HttpHelpers.concatenateUriPath(baseUri, requestPath);
        this.initialReconnectDelay = initialReconnectDelaySeconds;

        // The stream will lazily start when `next` is called.
    }

    private void startStream() {
        Headers headers = httpProperties.toHeadersBuilder()
                .add("Accept", "text/event-stream")
                .build();

        HttpConnectStrategy connectStrategy = ConnectStrategy.http(streamUri)
                .headers(headers)
                .clientBuilderActions(clientBuilder -> {
                    httpProperties.applyToHttpClientBuilder(clientBuilder);
                    // Add interceptor to inject selector and filter query parameters on each request
                    clientBuilder.addInterceptor(chain -> {
                        okhttp3.Request originalRequest = chain.request();
                        Selector selector = selectorSource.getSelector();

                        URI currentUri = originalRequest.url().uri();
                        URI updatedUri = currentUri;

                        // Add selector query parameters if the selector is not empty
                        if (!selector.isEmpty()) {
                            updatedUri = HttpHelpers.addQueryParam(updatedUri, "version", String.valueOf(selector.getVersion()));
                            if (selector.getState() != null && !selector.getState().isEmpty()) {
                                updatedUri = HttpHelpers.addQueryParam(updatedUri, "state", selector.getState());
                            }
                        }

                        // Add the payloadFilter query parameter if present and non-empty
                        if (payloadFilter != null && !payloadFilter.isEmpty()) {
                            updatedUri = HttpHelpers.addQueryParam(updatedUri, "filter", payloadFilter);
                        }

                        // If no parameters were added, proceed with the original request
                        if (updatedUri.equals(currentUri)) {
                            return chain.proceed(originalRequest);
                        }

                        okhttp3.Request newRequest = originalRequest.newBuilder()
                                .url(updatedUri.toString())
                                .build();
                        return chain.proceed(newRequest);
                    });
                })
                .readTimeout(DEAD_CONNECTION_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);

        EventSource.Builder builder = new EventSource.Builder(connectStrategy)
                .errorStrategy(ErrorStrategy.alwaysContinue())
                // alwaysContinue means we want EventSource to give us a FaultEvent rather
                // than throwing an exception if the stream fails
                .logger(logger)
                .readBufferSize(5000)
                .streamEventData(true)
                .expectFields("event")
                .retryDelay(initialReconnectDelay.toMillis(), TimeUnit.MILLISECONDS);

        eventSource = builder.build();

        Thread thread = getRunThread();
        thread.start();
    }

    @NotNull
    private Thread getRunThread() {
        Thread thread = new Thread(() -> {
            try {
                for (StreamEvent event : eventSource.anyEvents()) {
                    if (!handleEvent(event)) {
                        break;
                    }
                }
            } catch (Exception e) {
                // Any uncaught runtime exception at this point would be coming from es.anyEvents().
                // That's not expected-- all deliberate EventSource exceptions are checked exceptions.
                // So we have to assume something is wrong that we can't recover from at this point,
                // and just let the thread terminate. That's better than having the thread be killed
                // by an uncaught exception.
                if (closed.get()) {
                    return; // ignore any exception that's just a side effect of stopping the EventSource
                }
                logger.error("Stream thread ended with exception: {}", LogValues.exceptionSummary(e));
                logger.debug(LogValues.exceptionTrace(e));

                DataSourceStatusProvider.ErrorInfo errorInfo = new DataSourceStatusProvider.ErrorInfo(
                        DataSourceStatusProvider.ErrorKind.UNKNOWN,
                        0,
                        e.toString(),
                        Instant.now()
                );
                // We aren't restarting the event source here. We aren't going to automatically recover, so the
                // data system will move to the next source when it determined this source is unhealthy.
                resultQueue.put(FDv2SourceResult.interrupted(errorInfo));
            } finally {
                eventSource.close();
            }
        });
        thread.setName("LaunchDarkly-FDv2-streaming-synchronizer");
        // TODO: Implement thread priority.
        //streamThread.setPriority();
        thread.setDaemon(true);
        return thread;
    }

    @Override
    public CompletableFuture<FDv2SourceResult> next() {
        // If we are already closed, don't start the stream.
        if (!started.getAndSet(true) && !closed.get()) {
            startStream();
        }
        return CompletableFuture.anyOf(shutdownFuture, resultQueue.take())
                .thenApply(result -> (FDv2SourceResult) result);
    }

    @Override
    public void close() {
        if (closed.getAndSet(true)) {
            return; // already shutdown
        }

        shutdownFuture.complete(FDv2SourceResult.shutdown());

        // If the synchronizer was never started, then the event source could be null.
        if (eventSource != null) {
            try {
                eventSource.close();
            } catch (Exception e) {
                logger.debug("Error closing event source during shutdown: {}", LogValues.exceptionSummary(e));
            }
        }
    }

    private boolean handleEvent(StreamEvent event) {
        if (event instanceof MessageEvent) {
            handleMessage((MessageEvent) event);
            return true;
        } else if (event instanceof FaultEvent) {
            return handleError(((FaultEvent) event).getCause());
        }
        return true;
    }

    private void handleMessage(MessageEvent event) {
        // Parse the event - this is the only place SerializationException can be thrown
        String eventName = event.getEventName();
        FDv2Event fdv2Event;
        try {
            fdv2Event = parseFDv2Event(eventName, event.getDataReader());
        } catch (SerializationException e) {
            logger.error("Failed to parse FDv2 event: {}", LogValues.exceptionSummary(e));
            interruptedWithException(e, DataSourceStatusProvider.ErrorKind.INVALID_DATA);
            return;
        }

        // Handle the event with the protocol handler - this can throw exceptions on protocol errors
        FDv2ProtocolHandler.IFDv2ProtocolAction action;
        try {
            action = protocolHandler.handleEvent(fdv2Event);
        } catch (Exception e) {
            // Protocol handler threw exception processing the event - treat as invalid data
            logger.error("FDv2 protocol handler error: {}", LogValues.exceptionSummary(e));
            interruptedWithException(e, DataSourceStatusProvider.ErrorKind.INVALID_DATA);
            return;
        }

        FDv2SourceResult result = null;
        switch (action.getAction()) {
            case CHANGESET:
                FDv2ProtocolHandler.FDv2ActionChangeset changeset = (FDv2ProtocolHandler.FDv2ActionChangeset) action;
                try {
                    // TODO: Environment ID.
                    DataStoreTypes.ChangeSet<DataStoreTypes.ItemDescriptor> converted =
                            FDv2ChangeSetTranslator.toChangeSet(changeset.getChangeset(), logger, null);
                    result = FDv2SourceResult.changeSet(converted);
                } catch (Exception e) {
                    logger.error("Failed to convert FDv2 changeset: {}", LogValues.exceptionSummary(e));
                    logger.debug(LogValues.exceptionTrace(e));
                    DataSourceStatusProvider.ErrorInfo conversionError = new DataSourceStatusProvider.ErrorInfo(
                            DataSourceStatusProvider.ErrorKind.INVALID_DATA,
                            0,
                            e.toString(),
                            Instant.now()
                    );
                    result = FDv2SourceResult.interrupted(conversionError);
                    restartStream();
                }
                break;

            case ERROR:
                // In the case of an error, the protocol handler discards the result and we remain connected.
                // We log the error to help with debugging.
                FDv2ProtocolHandler.FDv2ActionError error = (FDv2ProtocolHandler.FDv2ActionError) action;
                logger.error("Received error from server: {} - {}", error.getId(), error.getReason());
                break;

            case GOODBYE:
                FDv2ProtocolHandler.FDv2ActionGoodbye goodbye = (FDv2ProtocolHandler.FDv2ActionGoodbye) action;
                result = FDv2SourceResult.goodbye(goodbye.getReason());
                // We drop this current connection and attempt to restart the stream.
                restartStream();
                break;

            case INTERNAL_ERROR:
                FDv2ProtocolHandler.FDv2ActionInternalError internalErrorAction = (FDv2ProtocolHandler.FDv2ActionInternalError) action;
                DataSourceStatusProvider.ErrorKind kind = DataSourceStatusProvider.ErrorKind.UNKNOWN;
                switch (internalErrorAction.getErrorType()) {
                    case MISSING_PAYLOAD:
                    case JSON_ERROR:
                        kind = DataSourceStatusProvider.ErrorKind.INVALID_DATA;
                        break;
                    case UNKNOWN_EVENT:
                    case IMPLEMENTATION_ERROR:
                    case PROTOCOL_ERROR:
                        break;
                }
                DataSourceStatusProvider.ErrorInfo internalError = new DataSourceStatusProvider.ErrorInfo(
                        kind,
                        0,
                        "Internal error during FDv2 event processing",
                        Instant.now()
                );
                result = FDv2SourceResult.interrupted(internalError);
                restartStream();
                break;

            case NONE:
                // Continue processing events, don't queue anything
                break;
        }

        if (result != null) {
            resultQueue.put(result);
        }
    }

    private void interruptedWithException(Exception e, DataSourceStatusProvider.ErrorKind kind) {
        logger.debug(LogValues.exceptionTrace(e));
        DataSourceStatusProvider.ErrorInfo errorInfo = new DataSourceStatusProvider.ErrorInfo(
                kind,
                0,
                e.toString(),
                Instant.now()
        );
        resultQueue.put(FDv2SourceResult.interrupted(errorInfo));
        restartStream();
    }

    private boolean handleError(StreamException e) {
        if (e instanceof StreamClosedByCallerException) {
            // We closed it ourselves (shutdown was called)
            return false;
        }

        if (e instanceof StreamHttpErrorException) {
            int status = ((StreamHttpErrorException) e).getCode();
            DataSourceStatusProvider.ErrorInfo errorInfo = DataSourceStatusProvider.ErrorInfo.fromHttpError(status);

            boolean recoverable = checkIfErrorIsRecoverableAndLog(logger,
                    "HTTP error " + status,
                    "in FDv2 streaming connection",
                    status,
                    "will retry");

            if (!recoverable) {
                resultQueue.put(FDv2SourceResult.terminalError(errorInfo));
                return false;
            } else {
                // Queue as INTERRUPTED to indicate temporary failure
                resultQueue.put(FDv2SourceResult.interrupted(errorInfo));
                return true; // allow reconnect
            }
        }

        // Network or other error - queue as INTERRUPTED and allow reconnect
        logger.warn("Stream error: {}", LogValues.exceptionSummary(e));
        logger.debug(LogValues.exceptionTrace(e));
        DataSourceStatusProvider.ErrorInfo errorInfo = new DataSourceStatusProvider.ErrorInfo(
                DataSourceStatusProvider.ErrorKind.NETWORK_ERROR,
                0,
                e.toString(),
                Instant.now()
        );
        resultQueue.put(FDv2SourceResult.interrupted(errorInfo));
        return true; // allow reconnect
    }

    private void restartStream() {
        Objects.requireNonNull(eventSource, "eventSource must not be null");
        eventSource.interrupt();
        protocolHandler.reset();
    }

    private FDv2Event parseFDv2Event(String eventName, Reader eventDataReader) throws SerializationException {
        try {
            JsonReader reader = new JsonReader(eventDataReader);
            FDv2Event event = new FDv2Event(
                    eventName,
                    com.launchdarkly.sdk.internal.GsonHelpers.gsonInstance().fromJson(
                            reader,
                            com.google.gson.JsonElement.class));
            reader.close();
            return event;
        } catch (Exception e) {
            throw new SerializationException(e);
        }
    }
}
