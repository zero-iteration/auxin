package dev.auxin.staticscan.tier2;

import java.util.regex.Pattern;

/**
 * One {@code --tier2} / {@code --tier2-exclude} glob, compiled.
 *
 * <p>The surface is {@code fully.qualified.Class#method} -- deliberately <b>not</b> the descriptor.
 * An operator asking for {@code com.acme.pricing.RateSelector#pick} means "time that method", and
 * making them spell {@code (Ljava/util/List;)Lcom/acme/Rate;} to say it would turn a routine request
 * into a bytecode exercise. The consequence is stated rather than hidden: a pattern selects
 * <em>every overload</em> of a matching name, and each overload costs its own ~80ns per call.
 *
 * <p>The two wildcards, and why they are not the same wildcard:
 * <ul>
 *   <li>{@code *} matches within one package segment. {@code com.acme.*.Handler} matches
 *       {@code com.acme.web.Handler} and does <b>not</b> match {@code com.acme.web.api.Handler}.</li>
 *   <li>{@code **} crosses package segments. {@code com.acme.**.Handler} matches both, and -- as in
 *       Ant and every build tool that copied it -- {@code **.} also matches <em>zero</em> segments,
 *       so it matches {@code com.acme.Handler} too. Anything else makes the obvious pattern for
 *       "every repository under com.acme" silently miss the ones that sit directly in
 *       {@code com.acme}, and a silently-missing timing is the failure this project exists to
 *       avoid.</li>
 * </ul>
 *
 * <p>{@code $} is an ordinary character here, not a separator: {@code com.acme.*} matches the nested
 * class {@code com.acme.Outer$Inner}. Nested classes are still classes, and no wildcard convention
 * anywhere treats them as a deeper package.
 *
 * <p>Matching is case-sensitive, because Java identifiers are.
 */
public final class MethodPattern {

    /** Separates the class glob from the method glob. Legal in neither a class nor a method name. */
    public static final char SEPARATOR = '#';

    private final String pattern;
    private final Pattern classRegex;
    private final Pattern methodRegex;

    private MethodPattern(String pattern, Pattern classRegex, Pattern methodRegex) {
        this.pattern = pattern;
        this.classRegex = classRegex;
        this.methodRegex = methodRegex;
    }

    /**
     * Compiles {@code pattern}.
     *
     * @throws IllegalArgumentException if the pattern is not {@code <class-glob>#<method-glob>} with
     *         exactly one separator and both halves non-empty. A pattern that is merely a class name
     *         is rejected rather than assumed to mean {@code #*}: guessing would let
     *         {@code --tier2 com.acme.Big} quietly enrol every method of a large class against a
     *         budget the author believed they were spending on one.
     */
    public static MethodPattern compile(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            throw new IllegalArgumentException("a tier-2 pattern must not be empty");
        }
        int separator = pattern.indexOf(SEPARATOR);
        if (separator < 0) {
            throw new IllegalArgumentException("tier-2 pattern '" + pattern + "' has no '"
                    + SEPARATOR + "': expected <class-glob>" + SEPARATOR + "<method-glob>, "
                    + "e.g. 'com.acme.**.*Repository" + SEPARATOR + "*'");
        }
        if (pattern.indexOf(SEPARATOR, separator + 1) >= 0) {
            throw new IllegalArgumentException("tier-2 pattern '" + pattern + "' has more than one '"
                    + SEPARATOR + "'");
        }
        String classGlob = pattern.substring(0, separator);
        String methodGlob = pattern.substring(separator + 1);
        if (classGlob.isEmpty() || methodGlob.isEmpty()) {
            throw new IllegalArgumentException("tier-2 pattern '" + pattern
                    + "' needs a glob on both sides of '" + SEPARATOR + "'");
        }
        return new MethodPattern(pattern,
                Pattern.compile(translateClassGlob(classGlob)),
                Pattern.compile(translateMethodGlob(methodGlob)));
    }

    /** The pattern as the operator wrote it. This is what gets reported as the selection reason. */
    public String pattern() {
        return pattern;
    }

    /** Whether this pattern selects {@code className#methodName}, whatever its descriptor. */
    public boolean matches(String className, String methodName) {
        return classRegex.matcher(className).matches() && methodRegex.matcher(methodName).matches();
    }

    @Override
    public String toString() {
        return pattern;
    }

    private static String translateClassGlob(String glob) {
        StringBuilder regex = new StringBuilder(glob.length() * 2);
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c != '*') {
                appendLiteral(regex, c);
                i++;
                continue;
            }
            boolean doubled = i + 1 < glob.length() && glob.charAt(i + 1) == '*';
            if (!doubled) {
                // Within one segment: anything but a package dot.
                regex.append("[^.]*");
                i++;
            } else if (i + 2 < glob.length() && glob.charAt(i + 2) == '.') {
                // "**." consumes the dot with it so that zero segments is a match.
                regex.append("(?:[^.]+\\.)*");
                i += 3;
            } else {
                regex.append(".*");
                i += 2;
            }
        }
        return regex.toString();
    }

    /**
     * Method names contain no package dots, so there is nothing for {@code *} to stop at and no
     * distinction to draw between {@code *} and {@code **}.
     */
    private static String translateMethodGlob(String glob) {
        StringBuilder regex = new StringBuilder(glob.length() * 2);
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*') {
                regex.append(".*");
                while (i < glob.length() && glob.charAt(i) == '*') {
                    i++;
                }
            } else {
                appendLiteral(regex, c);
                i++;
            }
        }
        return regex.toString();
    }

    /**
     * Escapes everything that is not an identifier character. {@code $} (nested classes) and
     * {@code <} {@code >} ({@code <init>}) are the ones that actually turn up; escaping the whole
     * class of characters means a pattern can never be read as a regular expression by accident.
     */
    private static void appendLiteral(StringBuilder regex, char c) {
        if (!Character.isLetterOrDigit(c) && c != '_') {
            regex.append('\\');
        }
        regex.append(c);
    }
}
