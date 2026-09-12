package traceapp;

import javax.servlet.http.HttpServletRequest;

/** A request whose {@code getHeader} is the activation channel. */
public final class FakeRequest implements HttpServletRequest {

    private final String method;
    private final String uri;
    private final String traceHeader;

    public FakeRequest(String method, String uri, String traceHeader) {
        this.method = method;
        this.uri = uri;
        this.traceHeader = traceHeader;
    }

    @Override
    public String getHeader(String name) {
        return "X-Auxin-Trace".equalsIgnoreCase(name) ? traceHeader : null;
    }

    @Override public String getMethod() { return method; }

    @Override public String getRequestURI() { return uri; }
}
