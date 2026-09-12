package io.auxin.trace.runtime;

/**
 * The redaction pass. Two independent mechanisms, because they fail differently.
 *
 * <h3>1. Field-name denylist (the config-time gate)</h3>
 * Applied to every projection getter name at <b>config load</b>, not at capture time, and applied
 * to the label of every emitted observation. A refused getter is dropped loudly, once, naming
 * the type and the getter, so that the operator learns their projection config was narrowed
 * rather than discovering later that a field they expected is missing.
 *
 * <p>Matching is case-insensitive on a normalised form (non-alphanumerics removed, a leading
 * {@code get}/{@code is} dropped) and it is a <b>substring</b> test: {@code getCustomerEmail},
 * {@code email_address} and {@code EMailAddr} all normalise to something containing
 * {@code email}. Substring matching over-blocks — {@code getKeyCount} is refused because it
 * contains {@code key} — and that is the correct direction for a denylist whose failure mode is
 * a PII leak. {@code ax.trace.redact.allow} exists for the specific over-block, per name.
 *
 * <h3>2. Value-shape check (the capture-time gate)</h3>
 * A number or string that <i>looks</i> like an identifier is redacted whatever it is called,
 * because the name gate cannot see through {@code getRef}, {@code getCode} or {@code getNum}.
 * Shapes, deliberately conservative: 12-19 digit runs (payment cards, IBAN tails, Aadhaar),
 * 10-digit runs beginning 6-9 (Indian mobile numbers), anything containing {@code @} with a dot
 * after it, and PAN-shaped {@code AAAAA9999A}.
 *
 * <h3>What this is NOT</h3>
 * It is not the reason the structural capture is PII-safe. The structural capture is PII-safe
 * <b>by construction</b> — sizes, null-ness, enum names, primitive numbers, booleans and
 * exception class names carry no payload, and no code path in this module serialises an object.
 * Redaction is the second line of defence for the one place an operator can aim the tracer at a
 * real value: the per-service projection config. Both lines are load-bearing; neither is
 * sufficient alone.
 */
public final class Redaction {

    /**
     * The denylist. Substrings, normalised form, lower case.
     *
     * <p>There was no redaction denylist anywhere in the auxin tree when this module was written
     * (checked: {@code grep -rn 'redact|denylist|PII|scrub'} matched only prose in
     * {@code docs/}), so this is the first one and it is documented here rather than adopted.
     */
    private static final String[] DENY = {
            "password", "passwd", "pwd", "secret", "token", "apikey", "key", "credential",
            "authorization", "authorisation", "auth", "bearer", "cookie", "session", "signature",
            "salt", "hash", "otp", "pin", "cvv", "cvc", "card", "pan", "iban", "swift", "bic",
            "account", "acct", "routing", "ssn", "sin", "nin", "aadhaar", "aadhar", "pannumber",
            "passport", "licence", "license", "dob", "birth", "email", "mail", "phone", "mobile",
            "msisdn", "tel", "address", "addr", "postcode", "zip", "pincode", "latitude",
            "longitude", "lat", "lon", "geo", "name", "firstname", "lastname", "surname",
            "gender", "race", "religion", "salary", "income", "medical", "diagnosis", "health",
    };

    private static volatile String[] allow = new String[0];

    /** {@code ax.trace.redact.allow}: normalised names the operator has explicitly un-blocked. */
    public static void allow(java.util.List<String> names) {
        String[] a = new String[names.size()];
        for (int i = 0; i < names.size(); i++) a[i] = normalise(names.get(i));
        allow = a;
    }

    public static String[] allowed() { return allow.clone(); }

    /** @return true when a member with this name must never have its value emitted. */
    public static boolean deniedName(String name) {
        if (name == null) return true;
        String n = normalise(name);
        if (n.length() == 0) return true;
        String[] a = allow;
        for (int i = 0; i < a.length; i++) {
            if (a[i].equals(n)) return false;
        }
        for (int i = 0; i < DENY.length; i++) {
            if (n.contains(DENY[i])) return true;
        }
        return false;
    }

    /** Strips {@code get}/{@code is} and every non-alphanumeric, then lower-cases. */
    static String normalise(String name) {
        String s = name;
        if (s.startsWith("get") && s.length() > 3) s = s.substring(3);
        else if (s.startsWith("is") && s.length() > 2) s = s.substring(2);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') sb.append((char) (c + 32));
            else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
        }
        return sb.toString();
    }

    /**
     * @return true when this text looks like an identifier whatever it is called. Never throws;
     *         a null or empty string is not a shape.
     */
    public static boolean deniedShape(String text) {
        if (text == null || text.length() == 0) return false;
        int at = text.indexOf('@');
        if (at > 0 && text.indexOf('.', at) > at) return true;          // e-mail shaped
        int run = 0;
        int runStart = -1;
        for (int i = 0; i <= text.length(); i++) {
            char c = i < text.length() ? text.charAt(i) : ' ';
            if (c >= '0' && c <= '9') {
                if (run == 0) runStart = i;
                run++;
            } else {
                if (digitRunDenied(text, runStart, run)) return true;
                run = 0;
            }
        }
        return panShaped(text);
    }

    /** A long number is an identifier, not a measurement. */
    public static boolean deniedShape(long value) {
        long v = value < 0 ? -value : value;
        int digits = 1;
        while (v >= 10) { v /= 10; digits++; }
        if (digits >= 12) return true;                                   // card / Aadhaar / IBAN
        if (digits == 10) {
            long lead = value < 0 ? -value : value;
            while (lead >= 10) lead /= 10;
            return lead >= 6;                                            // Indian mobile
        }
        return false;
    }

    private static boolean digitRunDenied(String text, int start, int run) {
        if (run >= 12) return true;
        if (run == 10 && start >= 0) {
            char lead = text.charAt(start);
            return lead >= '6' && lead <= '9';
        }
        return false;
    }

    /** {@code AAAAA9999A} — an Indian PAN, and a shape nothing legitimate reports as a metric. */
    private static boolean panShaped(String t) {
        if (t.length() != 10) return false;
        for (int i = 0; i < 5; i++) if (!Character.isLetter(t.charAt(i))) return false;
        for (int i = 5; i < 9; i++) if (!Character.isDigit(t.charAt(i))) return false;
        return Character.isLetter(t.charAt(9));
    }

    private Redaction() { throw new AssertionError(); }
}
