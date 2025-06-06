package com.launchdarkly.sdk.internal.events;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.logging.LogValues;
import com.launchdarkly.sdk.internal.http.HttpHelpers;
import com.launchdarkly.sdk.internal.http.HttpProperties;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static com.launchdarkly.sdk.internal.http.HttpErrors.checkIfErrorIsRecoverableAndLog;
import static com.launchdarkly.sdk.internal.http.HttpErrors.httpErrorDescription;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * The default implementation of delivering JSON data to an LaunchDarkly event endpoint.
 * This is the only implementation that is used by the SDKs. It is abstracted out with an
 * interface for the sake of testability.
 */
public final class DefaultEventSender implements EventSender {
  /**
   * Default value for {@code retryDelayMillis} parameter.
   */
  public static final long DEFAULT_RETRY_DELAY_MILLIS = 1000;

  /**
   * Default value for {@code analyticsRequestPath} parameter, for the server-side SDK.
   * The Android SDK should modify this value.
   */
  public static final String DEFAULT_ANALYTICS_REQUEST_PATH = "/bulk";

  /**
   * Default value for {@code diagnosticRequestPath} parameter, for the server-side SDK.
   * The Android SDK should modify this value.
   */
  public static final String DEFAULT_DIAGNOSTIC_REQUEST_PATH = "/diagnostic";

  private static final String EVENT_SCHEMA_HEADER = "X-LaunchDarkly-Event-Schema";
  private static final String EVENT_SCHEMA_VERSION = "4";
  private static final String EVENT_PAYLOAD_ID_HEADER = "X-LaunchDarkly-Payload-ID";
  private static final MediaType JSON_CONTENT_TYPE = MediaType.parse("application/json; charset=utf-8");
  private static final SimpleDateFormat HTTP_DATE_FORMAT = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz",
      Locale.US); // server dates as defined by RFC-822/RFC-1123 use English day/month names
  private static final Object HTTP_DATE_FORMAT_LOCK = new Object(); // synchronize on this because DateFormat isn't thread-safe

  private static class CompressionResult<T> {
    final T data;
    final boolean wasCompressed;

    CompressionResult(T data, boolean wasCompressed) {
      this.data = data;
      this.wasCompressed = wasCompressed;
    }
  }

  private final OkHttpClient httpClient;
  private final boolean shouldCloseHttpClient;
  private final Headers baseHeaders;
  private final String analyticsRequestPath;
  private final String diagnosticRequestPath;
  final long retryDelayMillis; // visible for testing
  private final LDLogger logger;
  private final boolean enableGzipCompression;

  /**
   * Creates an instance.
   *
   * @param httpProperties the HTTP configuration
   * @param analyticsRequestPath the request path for posting analytics events
   * @param diagnosticRequestPath the request path for posting diagnostic events
   * @param retryDelayMillis retry delay, or zero to use the default
   * @param enableGzipCompression whether to enable gzip compression
   * @param logger the logger
   */
  public DefaultEventSender(
      HttpProperties httpProperties,
      String analyticsRequestPath,
      String diagnosticRequestPath,
      long retryDelayMillis,
      boolean enableGzipCompression,
      LDLogger logger
      ) {
    if (httpProperties.getSharedHttpClient() == null) {
      this.httpClient = httpProperties.toHttpClientBuilder().build();
      shouldCloseHttpClient = true;
    } else {
      this.httpClient = httpProperties.getSharedHttpClient();
      shouldCloseHttpClient = false;
    }
    this.logger = logger;
    this.enableGzipCompression = enableGzipCompression;

    this.baseHeaders = httpProperties.toHeadersBuilder()
        .add("Content-Type", "application/json")
        .build();

    this.analyticsRequestPath = analyticsRequestPath == null ? DEFAULT_ANALYTICS_REQUEST_PATH : analyticsRequestPath;
    this.diagnosticRequestPath = diagnosticRequestPath == null ? DEFAULT_DIAGNOSTIC_REQUEST_PATH : diagnosticRequestPath;

    this.retryDelayMillis = retryDelayMillis <= 0 ? DEFAULT_RETRY_DELAY_MILLIS : retryDelayMillis;
  }

  @Override
  public void close() throws IOException {
    if (shouldCloseHttpClient) {
      HttpProperties.shutdownHttpClient(httpClient);
    }
  }

  @Override
  public Result sendAnalyticsEvents(byte[] data, int eventCount, URI eventsBaseUri) {
    return sendEventData(false, data, eventCount, eventsBaseUri);
  }

  @Override
  public Result sendDiagnosticEvent(byte[] data, URI eventsBaseUri) {
    return sendEventData(true, data, 1, eventsBaseUri);
  }

  private CompressionResult<byte[]> compressData(byte[] data) {
    if (!enableGzipCompression) {
      return new CompressionResult<>(data, false);
    }

    try {
      ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
      try (GZIPOutputStream gzipStream = new GZIPOutputStream(byteStream)) {
        gzipStream.write(data);
      }
      byte[] compressedData = byteStream.toByteArray();
      return new CompressionResult<>(compressedData, true);
    } catch (IOException e) {
      logger.warn("Failed to compress event data, falling back to uncompressed: {}", e.toString());
      return new CompressionResult<>(data, false);
    }
  }

  private Result sendEventData(boolean isDiagnostic, byte[] data, int eventCount, URI eventsBaseUri) {
    if (data == null || data.length == 0) {
      // DefaultEventProcessor won't normally pass us an empty payload, but if it does, don't bother sending
      return new Result(true, false, null);
    }

    Headers.Builder headersBuilder = baseHeaders.newBuilder();
    String path;
    String description;

    if (isDiagnostic) {
      path = diagnosticRequestPath;
      description = "diagnostic event";
    } else {
      path = analyticsRequestPath;
      String eventPayloadId = UUID.randomUUID().toString();
      headersBuilder.add(EVENT_PAYLOAD_ID_HEADER, eventPayloadId);
      headersBuilder.add(EVENT_SCHEMA_HEADER, EVENT_SCHEMA_VERSION);
      description = String.format("%d event(s)", eventCount);
    }

    URI uri = HttpHelpers.concatenateUriPath(eventsBaseUri, path);
    CompressionResult<byte[]> compressionResult = compressData(data);
    RequestBody body = RequestBody.create(compressionResult.data, JSON_CONTENT_TYPE);
    boolean mustShutDown = false;

    if (compressionResult.wasCompressed) {
      headersBuilder.add("Content-Encoding", "gzip");
    }

    Headers headers = headersBuilder.build();
    logger.debug("Posting {} to {} with payload: {}", description, uri,
        LogValues.defer(new LazilyPrintedUtf8Data(data)));

    for (int attempt = 0; attempt < 2; attempt++) {
      if (attempt > 0) {
        logger.warn("Will retry posting {} after {}ms", description, retryDelayMillis);
        try {
          Thread.sleep(retryDelayMillis);
        } catch (InterruptedException e) { // COVERAGE: there's no way to cause this in tests
        }
      }

      Request request = new Request.Builder()
          .url(uri.toASCIIString())
          .post(body)
          .headers(headers)
          .build();

      long startTime = System.currentTimeMillis();
      String nextActionMessage = attempt == 0 ? "will retry" : "some events were dropped";
      String errorContext = "posting " + description;

      try (Response response = httpClient.newCall(request).execute()) {
        long endTime = System.currentTimeMillis();
        logger.debug("{} delivery took {} ms, response status {}", description, endTime - startTime, response.code());

        if (response.isSuccessful()) {
          return new Result(true, false, parseResponseDate(response));
        }

        String errorDesc = httpErrorDescription(response.code());
        boolean recoverable = checkIfErrorIsRecoverableAndLog(
            logger,
            errorDesc,
            errorContext,
            response.code(),
            nextActionMessage
            );
        if (!recoverable) {
          mustShutDown = true;
          break;
        }
      } catch (IOException e) {
        checkIfErrorIsRecoverableAndLog(logger, e.toString(), errorContext, 0, nextActionMessage);
      }
    }

    return new Result(false, mustShutDown, null);
  }

  private final Date parseResponseDate(Response response) {
    String dateStr = response.header("Date");
    if (dateStr != null) {
      try {
        // DateFormat is not thread-safe, so must synchronize
        synchronized (HTTP_DATE_FORMAT_LOCK) {
          return HTTP_DATE_FORMAT.parse(dateStr);
        }
      } catch (ParseException e) {
        logger.warn("Received invalid Date header from events service");
      }
    }
    return null;
  }

  private final class LazilyPrintedUtf8Data implements LogValues.StringProvider {
    private final byte[] data;

    LazilyPrintedUtf8Data(byte[] data) {
      this.data = data;
    }

    @Override
    public String get() {
      return data == null ? "" : new String(data, Charset.forName("UTF-8"));
    }
  }
}
