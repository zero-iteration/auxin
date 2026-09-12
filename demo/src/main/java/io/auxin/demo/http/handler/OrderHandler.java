package io.auxin.demo.http.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.auxin.demo.Counters;
import io.auxin.demo.service.OrderService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * {@code POST /orders?index=N} — the hot path.
 *
 * <p>The request index is supplied by the caller so that every branch downstream is a pure
 * function of it. That is what makes the ground truth reproducible under concurrency.
 */
public final class OrderHandler implements HttpHandler {

    private final OrderService orders;

    public OrderHandler(OrderService orders) {
        this.orders = orders;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Counters.inc("http.orders");
        int status = 200;
        String body;
        try {
            int index = parseIndex(exchange.getRequestURI().getQuery());
            int total = orders.placeOrder(index);
            if (total < 0) {
                status = 500;
                body = "{\"error\":\"downstream\"}";
            } else {
                body = "{\"total\":" + total + "}";
            }
        } catch (RuntimeException e) {
            // Fail-closed for the fixture, not for the product: an unexpected exception
            // here would otherwise be swallowed by the HTTP server and the run would look
            // healthy while producing garbage coverage.
            status = 500;
            body = "{\"error\":\"" + e.getClass().getName() + "\"}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** Live: called on every request. */
    private int parseIndex(String query) {
        if (query == null) {
            return 0;
        }
        for (String part : query.split("&")) {
            if (part.startsWith("index=")) {
                return Integer.parseInt(part.substring(6));
            }
        }
        return 0;
    }
}
