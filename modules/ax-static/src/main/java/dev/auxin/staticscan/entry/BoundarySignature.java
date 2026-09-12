package dev.auxin.staticscan.entry;

import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One framework boundary: a declaring type, a method name, and the exact descriptor shape that type
 * calls back into.
 *
 * <p>WHY the descriptor is part of the signature and not optional: {@code handle} is one of the most
 * common method names in any codebase. "Every method called {@code handle} on a class that
 * implements {@code HttpHandler}" would enrol a private {@code handle(String)} helper next to the
 * real boundary, and each enrolled method costs ~80ns on every call. The name alone is not evidence;
 * the name plus the descriptor the framework declared is.
 *
 * <p>The text form is {@code <type>#<method>(<parameter descriptors>)<return descriptor>} --
 * ordinary JVM descriptors, e.g.
 * {@code com.sun.net.httpserver.HttpHandler#handle(Lcom/sun/net/httpserver/HttpExchange;)V}. It is
 * both the built-in catalogue's storage form and what {@code --tier2-boundary} accepts, so the
 * flag can express nothing the catalogue cannot and vice versa.
 *
 * <h2>The one wildcard, and why it exists</h2>
 * A single {@code *} stands for <b>one reference type</b> (a descriptor starting with {@code L} or
 * {@code [}). It is there for exactly one reason: a signature inherited from a <em>generic</em>
 * supertype is erased differently in the implementor than in the declaration.
 * {@code Callable<String>.call()} is compiled as {@code call()Ljava/lang/String;} with a synthetic
 * bridge carrying the erased {@code ()Ljava/lang/Object;} -- and the bridge is skipped, because
 * bridges are never author-written bodies. Insisting on the declaration's erasure would therefore
 * match nothing but a method we refuse to instrument: a silent miss, which is the failure mode this
 * project exists to avoid. {@code ()*} matches the real override and still pins the arity.
 *
 * <p>{@code *} deliberately does <b>not</b> match a primitive or {@code void}: a type variable
 * always erases to a reference type, so allowing it would only widen the match beyond any real
 * inheritance. There is no wildcard for "any parameter list" -- an unknown arity is not a signature,
 * and if a framework has two boundary overloads, pass the flag twice.
 */
public final class BoundarySignature {

    /** Separates the declaring type from the method name. Legal in neither. */
    public static final char SEPARATOR = '#';

    /** Stands for one reference type, for a signature erased from a generic supertype. */
    public static final String ANY_REFERENCE = "*";

    private static final String SHAPE =
            "expected <type>" + SEPARATOR + "<method>(<parameter descriptors>)<return descriptor>, "
                    + "e.g. 'java.lang.Runnable" + SEPARATOR + "run()V'";

    private final String spec;
    private final String declaringType;
    private final String method;
    private final List<String> parameters;
    private final String returnType;

    private BoundarySignature(String spec,
                              String declaringType,
                              String method,
                              List<String> parameters,
                              String returnType) {
        this.spec = spec;
        this.declaringType = declaringType;
        this.method = method;
        this.parameters = Collections.unmodifiableList(new ArrayList<>(parameters));
        this.returnType = returnType;
    }

    /**
     * Parses the text form.
     *
     * @throws IllegalArgumentException if it is not
     *         {@code <type>#<method>(<parameter descriptors>)<return descriptor>}. A bare
     *         {@code Type#method} is rejected rather than read as "any descriptor": guessing is how
     *         an unrelated overload ends up carrying tier-2 latency nobody asked for.
     */
    public static BoundarySignature parse(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("a tier-2 boundary must not be empty: " + SHAPE);
        }
        String text = spec.trim();
        int separator = text.indexOf(SEPARATOR);
        if (separator < 0) {
            throw malformed(text, "has no '" + SEPARATOR + "'");
        }
        if (text.indexOf(SEPARATOR, separator + 1) >= 0) {
            throw malformed(text, "has more than one '" + SEPARATOR + "'");
        }
        String declaringType = text.substring(0, separator);
        requireTypeName(text, declaringType);

        String member = text.substring(separator + 1);
        int open = member.indexOf('(');
        int close = member.indexOf(')');
        if (open < 0 || close < 0) {
            throw malformed(text, "has no '(...)' descriptor; a method name on its own would match "
                    + "every unrelated overload of that name");
        }
        if (close < open) {
            throw malformed(text, "has ')' before '('");
        }
        String method = member.substring(0, open);
        if (method.isEmpty()) {
            throw malformed(text, "has no method name");
        }
        if (method.indexOf('.') >= 0 || method.indexOf('/') >= 0 || method.indexOf(';') >= 0) {
            throw malformed(text, "has '" + method + "' where a method name was expected");
        }

        List<String> parameters = tokens(text, member.substring(open + 1, close), false);
        List<String> returned = tokens(text, member.substring(close + 1), true);
        if (returned.size() != 1) {
            throw malformed(text, "must end in exactly one return descriptor, e.g. 'V'");
        }
        return new BoundarySignature(text, declaringType, method, parameters, returned.get(0));
    }

    /** The text form, exactly as the catalogue or the operator wrote it. */
    public String spec() {
        return spec;
    }

    /**
     * Dotted name of the interface or superclass that declares this boundary. This is the type an
     * implementor must (transitively) extend or implement.
     */
    public String declaringType() {
        return declaringType;
    }

    public String method() {
        return method;
    }

    /**
     * The {@code kind} reported for a match: the simple name of the declaring type, e.g.
     * {@code HttpHandler}.
     *
     * <p>Same convention as {@link EntryPointCatalog}, which reports the simple name of the
     * annotation that matched. The evidence is named after the thing that produced it, so the
     * manifest reads {@code entryPoint:HttpHandler} next to {@code entryPoint:GetMapping} and an
     * operator can tell which mechanism found the method.
     */
    public String kind() {
        String withoutPackage = declaringType.substring(declaringType.lastIndexOf('.') + 1);
        int dollar = withoutPackage.lastIndexOf('$');
        return dollar < 0 ? withoutPackage : withoutPackage.substring(dollar + 1);
    }

    /** Whether a declared method with this name and descriptor is this boundary's callback. */
    public boolean matches(String methodName, String descriptor) {
        if (!method.equals(methodName)) {
            return false;
        }
        Type[] arguments;
        Type returned;
        try {
            arguments = Type.getArgumentTypes(descriptor);
            returned = Type.getReturnType(descriptor);
        } catch (RuntimeException e) {
            // A descriptor read out of a class file is well formed by construction; refusing to
            // match is still better than failing the whole scan over one unreadable member.
            return false;
        }
        if (arguments.length != parameters.size()) {
            return false;
        }
        for (int i = 0; i < arguments.length; i++) {
            if (!tokenMatches(parameters.get(i), arguments[i].getDescriptor())) {
                return false;
            }
        }
        return tokenMatches(returnType, returned.getDescriptor());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BoundarySignature)) {
            return false;
        }
        BoundarySignature other = (BoundarySignature) o;
        return declaringType.equals(other.declaringType)
                && method.equals(other.method)
                && parameters.equals(other.parameters)
                && returnType.equals(other.returnType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(declaringType, method, parameters, returnType);
    }

    @Override
    public String toString() {
        return spec;
    }

    private static boolean tokenMatches(String token, String descriptor) {
        if (ANY_REFERENCE.equals(token)) {
            return descriptor.charAt(0) == 'L' || descriptor.charAt(0) == '[';
        }
        return token.equals(descriptor);
    }

    /** Splits a descriptor list into one token per type, so arity is checked and not guessed. */
    private static List<String> tokens(String spec, String text, boolean allowVoid) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '*') {
                tokens.add(ANY_REFERENCE);
                i++;
                continue;
            }
            int start = i;
            while (i < text.length() && text.charAt(i) == '[') {
                i++;
            }
            if (i >= text.length()) {
                throw malformed(spec, "has an array descriptor with no element type");
            }
            char element = text.charAt(i);
            if (element == 'L') {
                int end = text.indexOf(';', i);
                if (end < 0) {
                    throw malformed(spec, "has an object descriptor with no ';' -- "
                            + "descriptors are slashed and terminated, e.g. 'Ljava/lang/String;'");
                }
                i = end + 1;
            } else if ("BCDFIJSZ".indexOf(element) >= 0
                    || (element == 'V' && allowVoid && start == i)) {
                i++;
            } else {
                throw malformed(spec, "has '" + element + "' where a type descriptor was expected");
            }
            tokens.add(text.substring(start, i));
        }
        if (allowVoid && tokens.isEmpty()) {
            throw malformed(spec, "has no return descriptor");
        }
        return tokens;
    }

    private static void requireTypeName(String spec, String declaringType) {
        if (declaringType.isEmpty()) {
            throw malformed(spec, "has no type before '" + SEPARATOR + "'");
        }
        if (declaringType.indexOf('/') >= 0) {
            throw malformed(spec, "names the type in slashed form; use the dotted name, "
                    + "e.g. 'com.sun.net.httpserver.HttpHandler'");
        }
        if (declaringType.indexOf('(') >= 0 || declaringType.indexOf(';') >= 0) {
            throw malformed(spec, "has '" + declaringType + "' where a type name was expected");
        }
    }

    private static IllegalArgumentException malformed(String spec, String problem) {
        return new IllegalArgumentException(
                "tier-2 boundary '" + spec + "' " + problem + ": " + SHAPE);
    }
}
