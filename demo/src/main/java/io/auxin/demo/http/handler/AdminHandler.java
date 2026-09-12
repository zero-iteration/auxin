package io.auxin.demo.http.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.auxin.demo.Counters;
import io.auxin.demo.service.OrderService;
import io.auxin.demo.util.Ids;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * {@code /admin/*} — the infrequent endpoints.
 *
 * <p>This is the shape that breaks pod-subset sampling in production: an endpoint that a
 * single operator hits, on one pod, once a quarter. Here it is reproduced deterministically
 * so a harness can prove the agent catches it.
 */
public final class AdminHandler implements HttpHandler {

    private final OrderService orders;

    public AdminHandler(OrderService orders) {
        this.orders = orders;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Counters.inc("http.admin");
        String path = exchange.getRequestURI().getPath();
        String body;
        if (path.endsWith("/rebuild")) {
            body = rebuildIndex();
        } else if (path.endsWith("/threads")) {
            // Route exists; the traffic driver never calls it. See dumpThreads().
            body = dumpThreads();
        } else {
            body = status();
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /**
     * GROUND TRUTH — RARE. Hit once per request out of every 200, plus once at shutdown.
     */
    public String status() {
        Counters.inc("rare.adminStatus");
        return "{\"placed\":" + orders.placedCount() + "}";
    }

    /**
     * GROUND TRUTH — RARE. Called EXACTLY ONCE per run, by the traffic driver, after the
     * load loop finishes. The guaranteed-once case: if a coverage agent misses this one,
     * it is not a sampling problem, it is a bug.
     */
    public String rebuildIndex() {
        Counters.inc("rare.adminRebuild");
        return "{\"rebuilt\":\"" + Ids.newId("idx") + "\"}";
    }

    /**
     * GROUND TRUTH — DEAD.
     *
     * <p>Reachable from {@link #handle} through a route that is never requested: an HTTP
     * entry point that exists, is wired up, and never runs. Entry-point enumeration alone
     * would mark this live.
     */
    public String dumpThreads() {
        Counters.inc("dead.dumpThreads");
        return "{\"threads\":" + Thread.activeCount() + "}";
    }
}
