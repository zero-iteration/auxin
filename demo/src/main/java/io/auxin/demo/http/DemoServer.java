package io.auxin.demo.http;

import com.sun.net.httpserver.HttpServer;
import io.auxin.demo.http.handler.AdminHandler;
import io.auxin.demo.http.handler.HealthHandler;
import io.auxin.demo.http.handler.OrderHandler;
import io.auxin.demo.service.OrderService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The JDK's own HTTP server ({@code com.sun.net.httpserver}). No framework, therefore no
 * CGLIB proxies, no annotation scanning, and a class graph small enough that "this method
 * never ran" is a statement about the application rather than about the framework.
 */
public final class DemoServer {

    private final HttpServer server;
    private final ExecutorService pool;

    public DemoServer(int port, OrderService orderService) throws IOException {
        // Bind to loopback only: this is a test fixture, not a service.
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 128);
        AtomicInteger n = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "demo-http-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.createContext("/healthz", new HealthHandler());
        server.createContext("/orders", new OrderHandler(orderService));
        server.createContext("/admin", new AdminHandler(orderService));
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
        pool.shutdownNow();
        try {
            pool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** GROUND TRUTH — DEAD. Diagnostics helper nobody wired to an endpoint. */
    public String describe() {
        return "DemoServer[" + server.getAddress() + "]";
    }
}
