package dev.auxin.staticscan.source;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parsing of {@code META-INF/services} provider-configuration files.
 *
 * <p>WHY shared between the jar and the directory source: the file format is specified by
 * {@link java.util.ServiceLoader} -- UTF-8, one class name per line, {@code #} starts a comment,
 * blanks ignored -- and two implementations of one specification is two chances to get it wrong.
 */
final class ServiceFiles {

    static final String PREFIX = "META-INF/services/";

    private ServiceFiles() {
    }

    /** {@code true} if {@code entryPath} names a provider-configuration file. */
    static boolean isServiceFile(String entryPath) {
        return entryPath.startsWith(PREFIX) && entryPath.length() > PREFIX.length()
                && entryPath.indexOf('/', PREFIX.length()) < 0;
    }

    /** The service interface name from a provider-configuration file path. */
    static String serviceNameOf(String entryPath) {
        return entryPath.substring(PREFIX.length());
    }

    /** Reads provider class names, dropping comments and blank lines. Does not close the stream. */
    static List<String> readProviders(InputStream in) throws IOException {
        List<String> providers = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            int comment = line.indexOf('#');
            if (comment >= 0) {
                line = line.substring(0, comment);
            }
            line = line.trim();
            if (!line.isEmpty()) {
                providers.add(line);
            }
        }
        return providers;
    }
}
