package dev.auxin.staticscan.entry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The framework types whose <em>implementation</em> is an entry point, and the exact signature each
 * one calls back into.
 *
 * <p>WHY this exists next to {@link EntryPointCatalog}: that catalogue is entirely
 * annotation-descriptor driven, and a large class of applications declares its boundary by
 * <b>implementing an interface</b> instead. A {@code com.sun.net.httpserver.HttpHandler}, a
 * {@code Servlet}, a {@code Filter}, a JMS {@code MessageListener}, a Netty or Undertow handler
 * carry no annotation at all. Under SCOPE-v3 auxin is the only agent in the runtime path, so this
 * scan is the only source of TPS, error rate and p90 that will ever exist for the artifact -- and
 * for those applications an annotation-only detector produced <em>zero</em> of it, everywhere, with
 * no error and nothing in the report to suggest anything was missing.
 *
 * <h2>Two bounds worth stating, because both are consequences of scanning one artifact</h2>
 * <ul>
 *   <li><b>The hierarchy stops at the artifact boundary.</b> Matching is done by walking
 *       {@link dev.auxin.staticscan.graph.ClassHierarchy#subtypesOf(String)}, which is built from
 *       what each scanned class <em>declares</em> as its superclass and interfaces. So a class whose
 *       direct interface is {@code com.sun.net.httpserver.HttpHandler} matches even though the JDK
 *       is not in the scan -- the declared name is the evidence and no resolution is needed. What
 *       cannot be seen is a hierarchy that passes <em>through</em> an unscanned type: if a
 *       dependency's {@code AbstractBaseHandler implements HttpHandler} lives in another jar, a
 *       class extending it is invisible here. That is the honest limit, and it is why the catalogue
 *       lists the common concrete base classes ({@code HttpServlet},
 *       {@code ChannelInboundHandlerAdapter}, {@code SimpleChannelInboundHandler}) alongside the
 *       interfaces they implement: those are the types applications name directly.</li>
 *   <li><b>The declaring type itself is never a match.</b> {@code subtypesOf} excludes it. If a
 *       shaded copy of {@code javax.servlet.http.HttpServlet} is inside the artifact, its own
 *       {@code doGet} -- which returns 405 -- is not an application boundary.</li>
 * </ul>
 *
 * <h2>{@code java.lang.Runnable#run()V} is known, and deliberately NOT on by default</h2>
 * It is the most-implemented interface in Java: every thread body, every executor task, every
 * block a compiler or a library desugars into one. Auto-selecting it would spend the 250-method
 * tier-2 budget on a mid-size application before reaching a single controller, and would put ~80ns
 * on call paths nobody asked to measure. It is available as {@link #RUNNABLE_RUN} -- pass
 * {@code --tier2-boundary java.lang.Runnable#run()V} to opt in -- and the same applies to any other
 * type an operator knows to be their own boundary.
 *
 * <p>{@code java.util.concurrent.Callable#call} <em>is</em> on by default, which is the one entry in
 * this list that is a judgement rather than a fact: a {@code Callable} is submitted, not called, so
 * it is a boundary in the same sense a listener is, and in practice an artifact declares a handful
 * of them rather than hundreds. An application that wraps every unit of work in one should say
 * {@code --tier2-exclude} and be explicit about it.
 */
public final class BoundaryCatalog {

    /** Known, and off by default. Pass it to {@code --tier2-boundary} to opt in. */
    public static final String RUNNABLE_RUN = "java.lang.Runnable#run()V";

    private static final List<String> DEFAULT_SPECS = List.of(
            // The JDK's own HTTP server. No annotations anywhere in it.
            "com.sun.net.httpserver.HttpHandler#handle(Lcom/sun/net/httpserver/HttpExchange;)V",

            // Servlet API, both spellings, because a partly-migrated application contains both.
            "javax.servlet.Servlet#service"
                    + "(Ljavax/servlet/ServletRequest;Ljavax/servlet/ServletResponse;)V",
            "jakarta.servlet.Servlet#service"
                    + "(Ljakarta/servlet/ServletRequest;Ljakarta/servlet/ServletResponse;)V",
            "javax.servlet.Filter#doFilter(Ljavax/servlet/ServletRequest;"
                    + "Ljavax/servlet/ServletResponse;Ljavax/servlet/FilterChain;)V",
            "jakarta.servlet.Filter#doFilter(Ljakarta/servlet/ServletRequest;"
                    + "Ljakarta/servlet/ServletResponse;Ljakarta/servlet/FilterChain;)V",

            // HttpServlet is a superclass, not an interface: applications extend it and override
            // one do* method per verb. Its own service() dispatches to these, so the override is
            // where the request actually lands.
            "javax.servlet.http.HttpServlet#doGet(Ljavax/servlet/http/HttpServletRequest;"
                    + "Ljavax/servlet/http/HttpServletResponse;)V",
            "javax.servlet.http.HttpServlet#doPost(Ljavax/servlet/http/HttpServletRequest;"
                    + "Ljavax/servlet/http/HttpServletResponse;)V",
            "javax.servlet.http.HttpServlet#doPut(Ljavax/servlet/http/HttpServletRequest;"
                    + "Ljavax/servlet/http/HttpServletResponse;)V",
            "javax.servlet.http.HttpServlet#doDelete(Ljavax/servlet/http/HttpServletRequest;"
                    + "Ljavax/servlet/http/HttpServletResponse;)V",
            "javax.servlet.http.HttpServlet#doHead(Ljavax/servlet/http/HttpServletRequest;"
                    + "Ljavax/servlet/http/HttpServletResponse;)V",
            "javax.servlet.http.HttpServlet#doOptions(Ljavax/servlet/http/HttpServletRequest;"
                    + "Ljavax/servlet/http/HttpServletResponse;)V",
            "javax.servlet.http.HttpServlet#doPatch(Ljavax/servlet/http/HttpServletRequest;"
                    + "Ljavax/servlet/http/HttpServletResponse;)V",
            "jakarta.servlet.http.HttpServlet#doGet(Ljakarta/servlet/http/HttpServletRequest;"
                    + "Ljakarta/servlet/http/HttpServletResponse;)V",
            "jakarta.servlet.http.HttpServlet#doPost(Ljakarta/servlet/http/HttpServletRequest;"
                    + "Ljakarta/servlet/http/HttpServletResponse;)V",
            "jakarta.servlet.http.HttpServlet#doPut(Ljakarta/servlet/http/HttpServletRequest;"
                    + "Ljakarta/servlet/http/HttpServletResponse;)V",
            "jakarta.servlet.http.HttpServlet#doDelete(Ljakarta/servlet/http/HttpServletRequest;"
                    + "Ljakarta/servlet/http/HttpServletResponse;)V",
            "jakarta.servlet.http.HttpServlet#doHead(Ljakarta/servlet/http/HttpServletRequest;"
                    + "Ljakarta/servlet/http/HttpServletResponse;)V",
            "jakarta.servlet.http.HttpServlet#doOptions(Ljakarta/servlet/http/HttpServletRequest;"
                    + "Ljakarta/servlet/http/HttpServletResponse;)V",
            "jakarta.servlet.http.HttpServlet#doPatch(Ljakarta/servlet/http/HttpServletRequest;"
                    + "Ljakarta/servlet/http/HttpServletResponse;)V",

            // Message-driven: the caller is a broker client, outside the artifact entirely.
            "javax.jms.MessageListener#onMessage(Ljavax/jms/Message;)V",
            "jakarta.jms.MessageListener#onMessage(Ljakarta/jms/Message;)V",

            "io.undertow.server.HttpHandler#handleRequest(Lio/undertow/server/HttpServerExchange;)V",

            // Netty. channelRead is declared on the interface and on the adapter applications
            // actually extend; channelRead0 is declared on SimpleChannelInboundHandler and is
            // generic, so the override's second parameter is the concrete message type.
            "io.netty.channel.ChannelInboundHandler#channelRead"
                    + "(Lio/netty/channel/ChannelHandlerContext;Ljava/lang/Object;)V",
            "io.netty.channel.ChannelInboundHandlerAdapter#channelRead"
                    + "(Lio/netty/channel/ChannelHandlerContext;Ljava/lang/Object;)V",
            "io.netty.channel.SimpleChannelInboundHandler#channelRead0"
                    + "(Lio/netty/channel/ChannelHandlerContext;*)V",

            // Submitted, never called. Generic, so the return type is the erasure of T.
            "java.util.concurrent.Callable#call()*");

    private final Map<String, List<BoundarySignature>> byDeclaringType;

    private BoundaryCatalog(Collection<BoundarySignature> signatures) {
        Map<String, List<BoundarySignature>> grouped = new LinkedHashMap<>();
        for (BoundarySignature signature : new LinkedHashSet<>(signatures)) {
            grouped.computeIfAbsent(signature.declaringType(), k -> new ArrayList<>())
                    .add(signature);
        }
        Map<String, List<BoundarySignature>> immutable = new LinkedHashMap<>();
        for (Map.Entry<String, List<BoundarySignature>> entry : grouped.entrySet()) {
            immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        this.byDeclaringType = Collections.unmodifiableMap(immutable);
    }

    /** The built-in catalogue, with no additions. This is the zero-configuration behaviour. */
    public static BoundaryCatalog withDefaults() {
        return of(List.of());
    }

    /**
     * The built-in catalogue plus {@code additionalSpecs}, in text form.
     *
     * <p>A spec identical to a built-in one is folded into it rather than doubling the entry, so
     * passing {@code --tier2-boundary} for something already known is harmless.
     *
     * @throws IllegalArgumentException if any spec is malformed; see
     *         {@link BoundarySignature#parse(String)}
     */
    public static BoundaryCatalog of(Collection<String> additionalSpecs) {
        List<BoundarySignature> signatures = new ArrayList<>();
        for (String spec : DEFAULT_SPECS) {
            signatures.add(BoundarySignature.parse(spec));
        }
        for (String spec : additionalSpecs) {
            signatures.add(BoundarySignature.parse(spec));
        }
        return new BoundaryCatalog(signatures);
    }

    /** Only these signatures; nothing built in. Exists so the rules can be tested in isolation. */
    public static BoundaryCatalog ofOnly(Collection<String> specs) {
        List<BoundarySignature> signatures = new ArrayList<>(specs.size());
        for (String spec : specs) {
            signatures.add(BoundarySignature.parse(spec));
        }
        return new BoundaryCatalog(signatures);
    }

    /** No boundary detection at all. */
    public static BoundaryCatalog empty() {
        return new BoundaryCatalog(List.of());
    }

    /** The built-in specs, in text form, for {@code --help} and for tests. */
    public static List<String> defaultSpecs() {
        return DEFAULT_SPECS;
    }

    /**
     * Signatures grouped by the type an implementor has to extend or implement, which is the shape
     * the detector needs: one {@code subtypesOf} walk per type rather than one per signature.
     */
    public Map<String, List<BoundarySignature>> byDeclaringType() {
        return byDeclaringType;
    }

    /** Every signature, in catalogue order. */
    public List<BoundarySignature> signatures() {
        List<BoundarySignature> all = new ArrayList<>();
        for (List<BoundarySignature> group : byDeclaringType.values()) {
            all.addAll(group);
        }
        return List.copyOf(all);
    }

    public boolean isEmpty() {
        return byDeclaringType.isEmpty();
    }
}
