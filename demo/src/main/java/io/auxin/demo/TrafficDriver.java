package io.auxin.demo;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drives deterministic traffic against the in-process server.
 *
 * <p>Stop condition: {@code elapsed >= seconds AND requests >= minRequests}. The minimum
 * matters — it is what guarantees every rare branch fires at least once. A duration-only
 * driver would make the fixture flaky on a fast or slow machine, and "the rare method did
 * not execute" would then be indistinguishable from "the agent missed it", which is the
 * one confusion this whole harness exists to avoid.
 */
public final class TrafficDriver {

    private final String baseUrl;
    private final int threads;
    private final int seconds;
    private final int minRequests;
    private final int maxRequests;
    private final AtomicInteger index = new AtomicInteger();

    public TrafficDriver(String baseUrl, int threads, int seconds, int minRequests, int maxRequests) {
        this.baseUrl = baseUrl;
        this.threads = threads;
        this.seconds = seconds;
        this.minRequests = minRequests;
        this.maxRequests = maxRequests;
    }

    public void run() throws InterruptedException {
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        List<Thread> workers = new ArrayList<>(threads);
        for (int t = 0; t < threads; t++) {
            Thread w = new Thread(() -> loop(deadline), "demo-driver-" + t);
            workers.add(w);
            w.start();
        }
        for (Thread w : workers) {
            w.join();
        }
        // Exactly once per run, after the load: the guaranteed-once rare endpoint.
        get("/admin/rebuild");
        Counters.inc("driver.finalRebuild");
    }

    private void loop(long deadline) {
        while (true) {
            int i = index.getAndIncrement();
            if (i >= maxRequests) {
                return;
            }
            if (System.nanoTime() >= deadline && i >= minRequests) {
                return;
            }
            post("/orders?index=" + i);
            if (i % 10 == 0) {
                get("/healthz");
            }
            if (i > 0 && i % 200 == 0) {
                get("/admin/status");
            }
        }
    }

    private void post(String path) {
        request("POST", path);
    }

    private void get(String path) {
        request("GET", path);
    }

    private void request(String method, String path) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(baseUrl + path).toURL().openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            // 500s are expected: the simulated downstream failure fires every 137th order.
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in != null) {
                byte[] buf = new byte[512];
                while (in.read(buf) > 0) {
                    // drain
                }
                in.close();
            }
            Counters.inc(code >= 400 ? "driver.responses.error" : "driver.responses.ok");
        } catch (IOException e) {
            Counters.inc("driver.ioError");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
