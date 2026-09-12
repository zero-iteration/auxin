package io.auxin.trace.transport;

import io.auxin.trace.config.TraceOptions;
import io.auxin.trace.runtime.TraceHealth;
import io.auxin.trace.util.TLog;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPOutputStream;

/**
 * {@code POST /v1/trace}, {@code Content-Type: application/json}, gzip.
 *
 * <h3>Why a separate endpoint</h3>
 * A trace document is a different shape, a different size class and a different failure mode
 * from an aggregate window. Sharing {@code /v1/ingest} would mean:
 * <ul>
 *   <li>a trace document that a stricter collector rejects also opens the circuit breaker that
 *       carries coverage — the aggregate path would go dark because someone traced a request;</li>
 *   <li>a 5 MB trace competing for the same request budget as a 40 KB window;</li>
 *   <li>one schema with a discriminator, which is how two formats end up validating each
 *       other's fields.</li>
 * </ul>
 * So: its own route, its own {@link HttpURLConnection}, its own circuit breaker, and no shared
 * state with {@code ax-agent}'s {@code HttpSender} whatsoever. A dead trace collector cannot
 * affect coverage ingest, and a dead coverage collector cannot affect traces.
 *
 * <p>{@code HttpURLConnection} for the same reason ax-agent uses it: it is in the JDK 8 baseline
 * and this module may not add a dependency.
 */
public final class TraceSender {

    private static final int FAILURE_THRESHOLD = 3;
    private static final long COOLDOWN_MS = 60000L;

    private final String url;
    private final int timeoutMs;
    private final boolean enabled;
    private final String token;

    private int consecutiveFailures;
    private long openUntilMs;
    private volatile long lastStatus = -1;
    private volatile String lastBody;

    public TraceSender(TraceOptions o) {
        this.url = o.collectorUrl;
        this.timeoutMs = o.collectorTimeoutMs;
        this.token = o.token;
        this.enabled = o.transportEnabled && o.collectorUrl != null && !o.collectorUrl.isEmpty();
    }

    public boolean enabled() { return enabled; }

    public long lastStatus() { return lastStatus; }

    /** The last document built, whether or not it was sent. Ops/test hook. */
    public String lastBody() { return lastBody; }

    public boolean circuitOpen() { return System.currentTimeMillis() < openUntilMs; }

    /** @return true when the collector accepted the document. Never throws. */
    public boolean send(String json) {
        lastBody = json;
        if (!enabled) return false;
        long now = System.currentTimeMillis();
        if (now < openUntilMs) {
            TraceHealth.DOCS_FAILED.incrementAndGet();
            TLog.debug("trace collector circuit open, dropping document");
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
            conn.setRequestProperty("User-Agent", "auxin-trace");
            // The same shared secret that authorises activation authorises ingest, so a trace
            // document cannot be injected into the collector by anything that could not have
            // asked for the trace in the first place.
            if (!token.isEmpty()) conn.setRequestProperty("X-Auxin-Trace-Token", token);
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
                TraceHealth.DOCS_SENT.incrementAndGet();
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
        TraceHealth.DOCS_FAILED.incrementAndGet();
        consecutiveFailures++;
        if (consecutiveFailures >= FAILURE_THRESHOLD) {
            openUntilMs = System.currentTimeMillis() + COOLDOWN_MS;
            consecutiveFailures = 0;
            TLog.warn("trace collector unreachable (" + why + "); circuit open for "
                    + (COOLDOWN_MS / 1000) + "s. Aggregate ingest is on a different endpoint and "
                    + "a different sender, and is unaffected.");
        } else {
            TLog.debug("trace document send failed: " + why);
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
