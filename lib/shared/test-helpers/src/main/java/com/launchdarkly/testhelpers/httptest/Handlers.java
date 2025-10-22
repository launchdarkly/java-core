package com.launchdarkly.testhelpers.httptest;

import java.nio.charset.Charset;
import java.util.concurrent.Semaphore;

/**
 * Factory methods for standard {@link Handler} implementations.
 */
public abstract class Handlers {
  /**
   * Creates a {@link Handler} that calls all of the specified handlers in order.
   * <p>
   * You can use this to chain together operations like {@link #status(int)} and
   * {@link #header(String, String)}.
   * 
   * @param handlers a series of handlers
   * @return a {@link Handler}
   */
  public static Handler all(Handler... handlers) {
    return ctx -> {
      for (Handler h: handlers) {
        h.apply(ctx);
      }
    };
  }
  
  /**
   * Creates a {@link Handler} that sets the HTTP response status.
   * 
   * @param status the status code
   * @return a {@link Handler}
   */
  public static Handler status(int status) {
    return ctx -> ctx.setStatus(status);
  }
  
  /**
   * Creates a {@link Handler} that sets a response header.
   * 
   * @param name the header name
   * @param value the header value
   * @return a {@link Handler}
   */
  public static Handler header(String name, String value) {
    return ctx -> ctx.setHeader(name, value);
  }
  
  /**
   * Creates a {@link Handler} that adds a response header, without overwriting previous values.
   * 
   * @param name the header name
   * @param value the header value
   * @return a {@link Handler}
   */
  public static Handler addHeader(String name, String value) {
    return ctx -> ctx.addHeader(name, value);
  }

  /**
   * Creates a {@link Handler} that sends the specified response body.
   * 
   * @param contentType response content type
   * @param body response body (null is equivalent to an empty array)
   * @return a {@link Handler}
   */
  public static Handler body(String contentType, byte[] body) {
    return ctx -> {
      ctx.setHeader("Content-Type", contentType);
      ctx.setHeader("Content-Length", String.valueOf(body == null ? 0 : body.length));
      if (body != null) {
        ctx.write(body);
      }
    };
  }

  /**
   * Creates a {@link Handler} that sends the specified response body.
   * <p>
   * The response is encoded with UTF-8 by default, but "charset" is not added to the Content-Type.
   * 
   * @param contentType response content type
   * @param body response body (may be null)
   * @return a {@link Handler}
   */
  public static Handler bodyString(String contentType, String body) {
    return bodyString(contentType, body, null);
  }

  /**
   * Creates a {@link Handler} that sends the specified response body.
   * <p>
   * If specified, the encoding's name is added to the Content-Type as the "charset".
   * 
   * @param contentType response content type
   * @param body response body (may be null)
   * @param encoding character encoding; if null, UTF-8 will be used
   * @return a {@link Handler}
   */
  public static Handler bodyString(String contentType, String body, Charset encoding) {
    return body(
        encoding == null ? contentType :
          (contentType.contains("charset=") ? contentType : contentType + ";charset=" + encoding.name().toLowerCase()),
        body == null ? null :
          body.getBytes(encoding == null ? Charset.forName("UTF-8") : encoding)
        );
  }
  
  /**
   * Creates a {@link Handler} that sends a response body with JSON content type.
   * 
   * @param json the JSON data
   * @return a {@link Handler}
   */
  public static Handler bodyJson(String json) {
    return bodyJson(json, null);
  }

  /**
   * Creates a {@link Handler} that sends a response body with JSON content type.
   * 
   * @param json the JSON data
   * @param encoding character encoding; if null, UTF-8 will be used
   * @return a {@link Handler}
   */
  public static Handler bodyJson(String json, Charset encoding) {
    return bodyString("application/json", json, encoding);
  }
  
  /**
   * Creates a {@link Handler} that starts writing a chunked response.
   * 
   * <pre><code>
   * Handler handler = Handlers.all(
   *     Handlers.startChunks("text/my-stream-data"),
   *     Handlers.writeChunkString("data1"),
   *     Handlers.writeChunkString("data2")
   * );
   * </code></pre>
   * 
   * @param contentType the content type
   * @param encoding character encoding to include in the Content-Type header, if any
   * @return a {@link Handler}
   */
  public static Handler startChunks(String contentType, Charset encoding) {
    return ctx -> {
      ctx.setHeader("Content-Type", encoding == null ? contentType :
        (contentType + ";charset=" + encoding.name().toLowerCase()));
      ctx.setChunked();
      ctx.write(null);
    };
  }
  
  /**
   * Creates a {@link Handler} that writes response data in a chunked response.
   * 
   * @param data the chunk data
   * @return a {@link Handler}
   */
  public static Handler writeChunk(byte[] data) {
    return ctx -> ctx.write(data);
  }
  
  /**
   * Creates a {@link Handler} that writes response data in a chunked response.
   * <p>
   * This always uses the default character encoding to conver the string to bytes. To
   * use a different encoding, do the conversion yourself and call {@link #writeChunk(byte[])}.
   * 
   * @param data the chunk data
   * @return a {@link Handler}
   */
  public static Handler writeChunkString(String data) {
    return writeChunk(data.getBytes());
  }
  
  /**
   * Creates a {@link Handler} that sleeps for the specified amount of time.
   * 
   * @param delayMillis how long to delay, in milliseconds
   * @return a {@link Handler}
   */
  public static Handler delay(long delayMillis) {
    return ctx -> {
      try {
        Thread.sleep(delayMillis);
      } catch (InterruptedException e) {}
    };
  }

  /**
   * Creates a {@link Handler} that waits until the specified semaphore is available.
   * This can be used to synchronize test logic so that the HTTP response does not
   * proceed until signaled to by the test.
   * 
   * @param semaphore the semaphore to wait on
   * @return a {@link Handler}
   */
  public static Handler waitFor(Semaphore semaphore) {
    return ctx -> {
      try {
        semaphore.acquire();
      } catch (InterruptedException e) {
        return;
      }
    };
  }
  
  /**
   * Creates a {@link Handler} that sleeps indefinitely, holding the connection open,
   * until the server is closed.
   * 
   * @return a {@link Handler}
   */
  public static Handler hang() {
    return ctx -> {
      while (true) {
        try {
          Thread.sleep(1000);
        } catch (InterruptedException e) {
          return;
        } 
      }
    };
  }

  /**
   * Creates a stateful {@link Handler} that delegates to each of the specified handlers in sequence
   * as each request is received.
   * <p>
   * Any requests that happen after the last handler in the list has been used will receive a
   * 500 error.
   * 
   * @param handlers a series of handlers
   * @return a {@link Handler}
   */
  public static Handler sequential(Handler...handlers) {
    return new SequentialHandler(handlers);
  }
  
  /**
   * Shortcut handlers for simulating a Server-Sent Events stream.
   */
  public static abstract class SSE {
    /**
     * Starts a chunked stream with the standard content type "text/event-stream",
     * and the charset UTF-8.
     * 
     * @return a {@link Handler}
     */
    public static Handler start() {
      return startChunks("text/event-stream", Charset.forName("UTF-8"));
    }
    
    /**
     * Writes an SSE comment line.
     * 
     * @param text the content that should appear after the colon
     * @return a {@link Handler}
     */
    public static Handler comment(String text) {
      return writeChunkString(":" + text + "\n");
    }
    
    /**
     * Writes an SSE event terminated by two newlines.
     * 
     * @param content the full event
     * @return a {@link Handler}
     */
    public static Handler event(String content) {
      return writeChunkString(content + "\n\n");
    }
    
    /**
     * Writes an SSE event created from individual fields.
     * 
     * @param message the "event" field
     * @param data the "data" field
     * @return a {@link Handler}
     */
    public static Handler event(String message, String data) {
      return event("event: " + message + "\ndata: " + data);
    }
    
    /**
     * Waits indefinitely without closing the stream. Equivalent to {@link Handlers#hang()}.
     * 
     * @return a {@link Handler}
     */
    public static Handler leaveOpen() {
      return hang();
    }
  }
}
