package io.auxin.agent.transport;

import io.auxin.agent.config.Options;
import io.auxin.agent.health.Health;
import io.auxin.agent.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPOutputStream;

/**
 * {@code POST /v1/ingest}, {@code Content-Type: application/json}, gzip (CONTRACTS section 2).
 *
 * <p>{@code HttpURLConnection} on purpose: it is in the JDK 8 baseline, needs no dependency, and
 * the agent may not add one. Runs on the drain thread; never on an application thread.
 *
 * <p>Circuit breaker: N consecutive failures open it for a cooldown, after which exactly one
 * probe request is allowed. A dead collector must cost the application nothing, and must never
 * produce an exception that reaches application code.
 */
public final class HttpSender {

    private static final int FAILURE_THRESHOLD = 5;
    private static final long COOLDOWN_MS = 60000L;

    private final String url;
    private final int timeoutMs;
    private final boolean enabled;

    private int consecutiveFailures;
    private long openUntilMs;
    private long lastStatus = -1;

    public HttpSender(Options options) {
        this.url = options.collectorUrl;
        this.timeoutMs = options.collectorTimeoutMs;
        this.enabled = options.transportEnabled && options.collectorUrl != null
                && !options.collectorUrl.isEmpty();
    }

    public boolean enabled() { return enabled; }

    public long lastStatus() { return lastStatus; }

    public boolean circuitOpen() { return System.currentTimeMillis() < openUntilMs; }

    /** @return true when the collector accepted the window. Never throws. */
    public boolean send(String json) {
        if (!enabled) return false;
        long now = System.currentTimeMillis();
        if (now < openUntilMs) {
            Log.debug("collector circuit open, dropping window");
            Health.flushFailure();
            return false;
        }
        HttpURLConnection conn = null;
        try {
            byte[] body = gzip(json);
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Content-Encoding", "gzip");
            conn.setRequestProperty("User-Agent", "auxin-agent");
            conn.setFixedLengthStreamingMode(body.length);
            OutputStream out = conn.getOutputStream();
            try {
                out.write(body);
            } finally {
                out.close();
            }
            int status = conn.getResponseCode();
            lastStatus = status;
            drain(conn);
            if (status >= 200 && status < 300) {
                consecutiveFailures = 0;
                Health.flushOk();
                return true;
            }
            fail("HTTP " + status);
            return false;
        } catch (Throwable t) {
            fail(String.valueOf(t));
            return false;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) { }
            }
        }
    }

    private void fail(String why) {
        Health.flushFailure();
        consecutiveFailures++;
        if (consecutiveFailures >= FAILURE_THRESHOLD) {
            openUntilMs = System.currentTimeMillis() + COOLDOWN_MS;
            consecutiveFailures = 0;
            Log.warn("collector unreachable (" + why + "); circuit open for " + (COOLDOWN_MS / 1000) + "s");
        } else {
            Log.debug("collector send failed: " + why);
        }
    }

    private static void drain(HttpURLConnection conn) {
        try {
            java.io.InputStream in = conn.getErrorStream();
            if (in == null) in = conn.getInputStream();
            if (in == null) return;
            byte[] buf = new byte[1024];
            while (in.read(buf) > 0) { /* drain so the connection can be reused */ }
            in.close();
        } catch (Throwable ignored) {
        }
    }

    static byte[] gzip(String json) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(512, json.length() / 4));
        GZIPOutputStream gz = new GZIPOutputStream(bos);
        try {
            gz.write(json.getBytes("UTF-8"));
        } finally {
            gz.close();
        }
        return bos.toByteArray();
    }
}
