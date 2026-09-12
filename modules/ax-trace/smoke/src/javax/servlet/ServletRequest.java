package javax.servlet;

/**
 * A MINIMAL STAND-IN for the real {@code javax.servlet.ServletRequest}, carrying only the
 * methods TraceGate actually reaches reflectively.
 *
 * <p><b>Why a stub and not the real servlet-api jar.</b> The point of this suite is to prove that
 * the entry instrumentation matches on the DESCRIPTOR — {@code doFilter(ServletRequest,
 * ServletResponse, FilterChain)V} — and a descriptor is a string. Compiling the target app
 * against these stubs produces byte-for-byte the same descriptor a real Tomcat filter has, with
 * no third-party jar anywhere in the build. {@code javax.*} is not a restricted package (only
 * {@code java.*} is), so defining it here is legal.
 *
 * <p>It is also the honest limit of this suite: it proves the SIGNATURE match and the activation
 * mechanics, not that a real container calls these methods where we think it does.
 */
public interface ServletRequest {
    String getHeader(String name);
    String getMethod();
    String getRequestURI();
}
