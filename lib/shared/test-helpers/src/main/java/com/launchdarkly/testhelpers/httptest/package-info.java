/**
 * A simple portable HTTP test server with response mocking.
 * <p>
 * This package provides a simple abstraction for setting up embedded HTTP test servers
 * that return programmed responses, and verifying that the expected requests have been
 * made in tests.
 * <p>
 * Although Java has a standard servlet API for representing HTTP requests and responses,
 * it does not have a built-in HTTP server implementation. Some other Java libraries
 * provide a mockable server, but these have generally not been suitable for LaunchDarkly's
 * testing needs due to limitations in their design (for instance, they generally do not
 * support streaming responses).
 * <p>
 * This package uses a fork of nanohttpd as its underlying implementation. Only the core
 * server implementation is used, without a webapp framework, so it is fairly lightweight
 * and has dependencies other than Java 7 or above. However, this implementation has only
 * been validated for server-side Java; it may not work correctly in Android.
 * <p>
 * It is possible to build a simple web service with this package, but it should not be used
 * for production services.
 * <p>
 * An {@link com.launchdarkly.testhelpers.httptest.HttpServer} is an HTTP server that
 * starts listening on an arbitrarily chosen port as soon as you create it. You should
 * normally do this inside a try-with-resources block to ensure that the server is shut
 * down when you're done with it. The server's {@link com.launchdarkly.testhelpers.httptest.HttpServer#getUri()}
 * method gives you the address for making your test requests.
 * <p>
 * You configure the server with a single {@link com.launchdarkly.testhelpers.httptest.Handler}
 * that receives all requests. The library provides a variety of handler implementations and
 * combinators, or you can define your own.
 * <h2>
 * Examples
 * </h2>
 * <p>
 * 1. Invariant response with error status
 * <pre><code>
 *     HttpServer server = HttpServer.start(Handlers.status(500));
 * </code></pre>
 * <p>
 * 2. Invariant response with status, headers, and body
 * <pre><code>
 *     HttpServer server = HttpServer.start(
 *         Handlers.all(
 *             Handlers.status(202),
 *             Handlers.header("Etag", "123"),
 *             Handlers.bodyString("text/plain", "thanks")
 *         )
 *     );
 * </code></pre>
 * <p>
 * 3. Verifying requests made to the server
 * <pre><code>
 *     try (HttpServer server = HttpServer.start(Handlers.status(200))) {
 *         doSomethingThatMakesARequest(server.getUri());
 *         doSomethingElseThatMakesARequest(server.getUri());
 *         
 *         RequestInfo request1 = server.getRecorder().requireRequest();
 *         assertEquals("/path1", request1.getPath());
 *         
 *         RequestInfo request2 = server.getRecorder().requireRequest();
 *         assertEquals("/path2", request2.getPath());
 *     }
 * </code></pre>
 * <p>
 * 4. Response with custom logic depending on the request
 * <pre><code>
 *     HttpServer server = HttpServer.start(
 *         ctx -&gt; {
 *             if (ctx.getRequest().getHeader("Header-Name").equals("good-value")) {
 *                 Handlers.status(200).apply(ctx);
 *             } else {
 *                 Handlers.status(400).apply(ctx);
 *             }
 *         }
 *     );
 * </code></pre>
 * <p>
 * 5. Simple routing to simulate two endpoints
 * <pre><code>
 *     SimpleRouter router = new SimpleRouter();
 *     router.add("/path1", Handlers.status(200));
 *     router.add("/path2", Handlers.status(500));
 *     HttpServer server = HttpServer.start(router);
 * </code></pre>
 * <p>
 * 6. Programmed sequence of responses
 * <pre><code>
 *     HttpServer server = HttpServer.start(
 *         Handlers.sequential(
 *             Handlers.status(200), // first request gets a 200
 *             Handlers.status(500)  // next request gets a 500
 *         )
 *     );
 * </code></pre>
 * <p>
 * 7. Changing server behavior during a test
 * <pre><code>
 *     HandlerSwitcher switcher = new HandlerSwitcher(Handlers.status(200));
 *     try (HttpServer server = HttpServer.start(switcher) {
 *         // Initially the server returns 200 for all requests
 *         
 *         switcher.setTarget(Handlers.status(500));
 *         // Now the server returns 500 for all requests
 *     }
 * </code></pre>
 */
package com.launchdarkly.testhelpers.httptest;
