package io.auxin.trace.instrument;

import java.util.ArrayList;
import java.util.List;

/**
 * The servlet entry points, matched by <b>(name, descriptor)</b>.
 *
 * <h3>Why signature matching and not the class hierarchy</h3>
 * {@code Filter} is an interface and {@code HttpServlet} is an abstract class; the methods that
 * actually run are on application and framework classes we have never heard of. Deciding
 * "does this class implement {@code javax.servlet.Filter}" inside a {@code ClassFileTransformer}
 * means either walking the supertype graph (which loads classes from inside a transform — the
 * {@code ClassCircularityError} and startup-CPU hazard PLAN-v2 bans COMPUTE_FRAMES for) or
 * trusting the {@code interfaces} array, which misses every indirect implementation.
 *
 * <p>A descriptor, by contrast, is already in the class file. {@code doFilter} taking exactly
 * {@code (ServletRequest, ServletResponse, FilterChain)} and returning void <i>is</i> the
 * servlet filter contract — the JVM will not let it be anything else — so the descriptor is a
 * sound and cheap proxy for the hierarchy question. This mirrors what {@code ax-static}'s
 * {@code BoundarySignature} does for its boundary catalogue.
 *
 * <p>Both namespaces are listed: {@code javax.servlet} (Servlet <= 4 / Spring Boot 2) and
 * {@code jakarta.servlet} (Servlet >= 5 / Spring Boot 3). Spring's own
 * {@code DispatcherServlet#doService} is included because a Spring application's real request
 * entry is that method, and a {@code Filter} chain may not be installed at all.
 */
public final class EntrySignatures {

    private static final String[] BUILT_IN = {
            // Filter#doFilter
            "doFilter(Ljavax/servlet/ServletRequest;Ljavax/servlet/ServletResponse;"
                    + "Ljavax/servlet/FilterChain;)V",
            "doFilter(Ljakarta/servlet/ServletRequest;Ljakarta/servlet/ServletResponse;"
                    + "Ljakarta/servlet/FilterChain;)V",
            // Servlet#service, and HttpServlet's protected overload
            "service(Ljavax/servlet/ServletRequest;Ljavax/servlet/ServletResponse;)V",
            "service(Ljakarta/servlet/ServletRequest;Ljakarta/servlet/ServletResponse;)V",
            "service(Ljavax/servlet/http/HttpServletRequest;"
                    + "Ljavax/servlet/http/HttpServletResponse;)V",
            "service(Ljakarta/servlet/http/HttpServletRequest;"
                    + "Ljakarta/servlet/http/HttpServletResponse;)V",
            // Spring's FrameworkServlet/DispatcherServlet
            "doService(Ljavax/servlet/http/HttpServletRequest;"
                    + "Ljavax/servlet/http/HttpServletResponse;)V",
            "doService(Ljakarta/servlet/http/HttpServletRequest;"
                    + "Ljakarta/servlet/http/HttpServletResponse;)V",
    };

    private final String[] signatures;

    public EntrySignatures(List<String> extra) {
        List<String> all = new ArrayList<String>();
        for (int i = 0; i < BUILT_IN.length; i++) all.add(BUILT_IN[i]);
        if (extra != null) {
            for (int i = 0; i < extra.size(); i++) {
                String s = extra.get(i).trim();
                if (s.length() > 0) all.add(s);
            }
        }
        this.signatures = all.toArray(new String[0]);
    }

    /**
     * @return the zero-based index of the argument holding the request, or -1 when this is not
     *         an entry method. Always argument 0 for every shape above; returned rather than
     *         assumed so that an operator-supplied signature can say otherwise later.
     */
    public int requestArg(String methodName, String desc) {
        String key = methodName + desc;
        for (int i = 0; i < signatures.length; i++) {
            if (signatures[i].equals(key)) return 0;
        }
        return -1;
    }

    public boolean isEntry(String methodName, String desc) {
        return requestArg(methodName, desc) >= 0;
    }

    public int count() { return signatures.length; }

    /** The built-in signatures, for the startup line and for the smoke suite to assert against. */
    public static String[] builtIn() { return BUILT_IN.clone(); }
}
