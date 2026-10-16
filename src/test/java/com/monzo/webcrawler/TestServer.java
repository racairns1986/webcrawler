package com.monzo.webcrawler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal local HTTP fixture for crawler tests.
 * <p>
 * Routes are registered per path and every request path is recorded, so a test
 * controls exactly which URLs exist and can assert exactly what was fetched.
 * The server binds to an ephemeral port on the IPv4 loopback address, so URLs
 * need no IPv6 brackets. {@link #aliasUrl} exposes the same server as
 * {@code localhost}, a different host name, so tests can check that the
 * crawler compares hosts by name; this relies on {@code localhost} resolving
 * to IPv4 first, which is the JVM default.
 */
final class TestServer implements AutoCloseable
{
    record Response(int status, Map<String, String> headers, byte[] body, long delayMillis)
    {
        Response delayedBy(long millis)
        {
            return new Response(status, headers, body, millis);
        }
    }

    private static final String LOOPBACK_IP = "127.0.0.1";

    private final HttpServer server;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Response> routes = new ConcurrentHashMap<>();
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());

    TestServer() throws IOException
    {
        server = HttpServer.create(new InetSocketAddress(LOOPBACK_IP, 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    /** Base URL on the IPv4 loopback address, for example {@code http://127.0.0.1:54321}. */
    String baseUrl()
    {
        return "http://" + LOOPBACK_IP + ":" + port();
    }

    int port()
    {
        return server.getAddress().getPort();
    }

    String url(String path)
    {
        return baseUrl() + path;
    }

    /** The same server reached as {@code localhost}; a different host name, so external to a crawl seeded on the IP. */
    String aliasUrl(String path)
    {
        return "http://localhost:" + server.getAddress().getPort() + path;
    }

    TestServer page(String path, String bodyHtml)
    {
        routes.put(path, html(bodyHtml));

        return this;
    }

    TestServer respond(String path, Response response)
    {
        routes.put(path, response);

        return this;
    }

    static Response html(String bodyHtml)
    {
        return raw(200, "text/html; charset=utf-8", "<html><body>" + bodyHtml + "</body></html>");
    }

    static Response redirect(int status, String location)
    {
        Map<String, String> headers = location == null ? Map.of() : Map.of("Location", location);

        return new Response(status, headers, new byte[0], 0);
    }

    static Response status(int code)
    {
        return raw(code, "text/html; charset=utf-8", "<html><body>status " + code + "</body></html>");
    }

    static Response raw(int status, String contentType, String body)
    {
        return new Response(status, Map.of("Content-Type", contentType), body.getBytes(StandardCharsets.UTF_8), 0);
    }

    /** Request paths in arrival order. */
    List<String> requests()
    {
        return List.copyOf(requests);
    }

    /** The highest number of requests that were being handled at the same moment. */
    int maxConcurrentRequests()
    {
        return maxInFlight.get();
    }

    long requestCount(String path)
    {
        return requests().stream().filter(path::equals).count();
    }

    private void handle(HttpExchange exchange) throws IOException
    {
        String path = exchange.getRequestURI().getPath();
        requests.add(path);
        Response response = routes.getOrDefault(path, status(404));

        int current = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(current, Math::max);
        try
        {
            delay(response);                 // the Thread.sleep, if any
        }
        finally
        {
            inFlight.decrementAndGet();      // before the response starts
        }

        send(exchange, response);            // headers, body, close
    }

    private static void delay(Response response)
    {
        if (response.delayMillis() > 0)
        {
            try
            {
                Thread.sleep(response.delayMillis());
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void send(HttpExchange exchange, Response response) throws IOException
    {
        response.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));

        if (response.body().length == 0)
        {
            exchange.sendResponseHeaders(response.status(), -1);
        }
        else
        {
            exchange.sendResponseHeaders(response.status(), response.body().length);
            try (OutputStream out = exchange.getResponseBody())
            {
                out.write(response.body());
            }
        }

        exchange.close();
    }

    @Override
    public void close()
    {
        server.stop(0);
        executor.shutdownNow();
    }
}
