package io.auxin.demo.http.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.auxin.demo.Counters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * {@code GET /healthz}.
 *
 * <p>Also the handler a Kubernetes livenessProbe would hit — which is why the injected
 * probe timings matter: under CFS throttling at startup this handler is exactly what
 * fails to answer within the 1-second default timeout.
 */
public final class HealthHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Counters.inc("http.healthz");
        byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
