package dev.auxin.staticscan;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compiles a small, deliberately awkward Java project at test time so the assertions run against
 * real javac output rather than hand-written bytecode.
 *
 * <p>WHY real compilation: every rule in ax-static keys off something javac decides -- which methods
 * get {@code ACC_BRIDGE}, where the {@code LineNumberTable} entries land, how a lambda becomes an
 * {@code invokedynamic}, whether a trivial accessor is three instructions. Hand-assembled fixtures
 * would let us test our beliefs about javac instead of javac.
 *
 * <p>The fixture declares its own copies of the framework annotations. Only the <b>descriptor</b>
 * matters to ax-static -- it never loads or resolves them -- so a local declaration with the right
 * fully-qualified name exercises exactly the code path a real Spring application would.
 */
final class FixtureProject {

    private final Path root;
    private final Path classesDir;

    private FixtureProject(Path root, Path classesDir) {
        this.root = root;
        this.classesDir = classesDir;
    }

    /** Directory of compiled classes, with the {@code META-INF/services} file in place. */
    Path classesDir() {
        return classesDir;
    }

    /** Packs {@link #classesDir()} into a jar and returns its path. */
    Path toJar() throws IOException {
        Path jar = root.resolve("fixture.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar));
             Stream<Path> walk = Files.walk(classesDir)) {
            List<Path> files = walk.filter(Files::isRegularFile).sorted().toList();
            for (Path file : files) {
                out.putNextEntry(new JarEntry(classesDir.relativize(file).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(file));
                out.closeEntry();
            }
        }
        return jar;
    }

    /** Writes and compiles the fixture under {@code tempDir}. */
    static FixtureProject compile(Path tempDir) throws IOException {
        Path sourceDir = tempDir.resolve("src/main/java");
        Path classesDir = tempDir.resolve("classes");
        Files.createDirectories(classesDir);

        List<String> sourcePaths = new ArrayList<>();
        for (Map.Entry<String, String> entry : sources().entrySet()) {
            Path file = sourceDir.resolve(entry.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
            sourcePaths.add(file.toString());
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "these tests need a JDK, not a JRE: no system Java compiler found");

        // --release is pinned rather than left to the running JDK on purpose. ASM 9.7.1 refuses
        // class files newer than it knows about, so a fixture compiled by a newer local JDK would
        // fail the scan for a reason that has nothing to do with the code under test. Pinning also
        // makes the fixture's bytecode -- and therefore every assertion about it -- reproducible.
        List<String> arguments = new ArrayList<>(List.of(
                "-g", "-nowarn", "-proc:none", "--release", "17", "-d", classesDir.toString()));
        arguments.addAll(sourcePaths);
        int status = compiler.run(null, null, System.err, arguments.toArray(new String[0]));
        assertTrue(status == 0, "fixture project failed to compile");

        Path services = classesDir.resolve("META-INF/services/com.acme.shipping.RateSource");
        Files.createDirectories(services.getParent());
        Files.writeString(services,
                "# providers for the rate lookup SPI\n"
                        + "com.acme.shipping.DefaultRateSource\n"
                        + "\n",
                StandardCharsets.UTF_8);

        return new FixtureProject(tempDir, classesDir);
    }

    /** Writes a single extra class file into the fixture, used for the generated-class test. */
    static void writeRaw(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        try (OutputStream out = Files.newOutputStream(target)) {
            out.write(bytes);
        }
    }

    private static Map<String, String> sources() {
        Map<String, String> sources = new LinkedHashMap<>();

        sources.put("org.springframework.web.bind.annotation.RequestMapping", annotation(
                "org.springframework.web.bind.annotation", "RequestMapping", "TYPE, METHOD"));
        sources.put("org.springframework.web.bind.annotation.GetMapping", annotation(
                "org.springframework.web.bind.annotation", "GetMapping", "METHOD"));
        sources.put("org.springframework.scheduling.annotation.Scheduled", annotation(
                "org.springframework.scheduling.annotation", "Scheduled", "METHOD"));
        sources.put("org.springframework.cache.annotation.Cacheable", annotation(
                "org.springframework.cache.annotation", "Cacheable", "TYPE, METHOD"));
        sources.put("javax.annotation.PostConstruct", annotation(
                "javax.annotation", "PostConstruct", "METHOD"));
        sources.put("org.junit.jupiter.api.Test", annotation(
                "org.junit.jupiter.api", "Test", "METHOD"));

        sources.put("com.acme.shipping.Rate", """
                package com.acme.shipping;

                public final class Rate {
                    private final int cents;

                    public Rate(int cents) {
                        this.cents = cents;
                    }

                    public int cents() {
                        return cents;
                    }
                }
                """);

        sources.put("com.acme.shipping.RateSource", """
                package com.acme.shipping;

                public interface RateSource {
                    Rate lookup(String zone);
                }
                """);

        sources.put("com.acme.shipping.DefaultRateSource", """
                package com.acme.shipping;

                public class DefaultRateSource implements RateSource {
                    @Override
                    public Rate lookup(String zone) {
                        if (zone == null) {
                            throw new IllegalArgumentException("zone");
                        }
                        return new Rate(zone.length() * 100);
                    }
                }
                """);

        sources.put("com.acme.shipping.internal.CachingRateSource", """
                package com.acme.shipping.internal;

                import com.acme.shipping.Rate;
                import com.acme.shipping.RateSource;
                import org.springframework.cache.annotation.Cacheable;

                public class CachingRateSource implements RateSource {
                    private final RateSource delegate;

                    public CachingRateSource(RateSource delegate) {
                        this.delegate = delegate;
                    }

                    @Cacheable
                    @Override
                    public Rate lookup(String zone) {
                        return delegate.lookup(zone);
                    }
                }
                """);

        sources.put("com.acme.shipping.RateController", """
                package com.acme.shipping;

                import javax.annotation.PostConstruct;
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RequestMapping;

                @RequestMapping
                public class RateController {
                    private final RateSource source;

                    public RateController(RateSource source) {
                        this.source = source;
                    }

                    @GetMapping
                    public Rate quote(String zone) {
                        return source.lookup(zone);
                    }

                    @Scheduled
                    public void refresh() {
                        warm("default");
                    }

                    @PostConstruct
                    public void init() {
                    }

                    public Rate describe(String zone) {
                        return source.lookup(zone);
                    }

                    private void warm(String zone) {
                        source.lookup(zone);
                    }
                }
                """);

        sources.put("com.acme.shipping.Launcher", """
                package com.acme.shipping;

                public final class Launcher {
                    public static void main(String[] args) {
                        System.out.println(new DefaultRateSource().lookup("A").cents());
                    }
                }
                """);

        sources.put("com.acme.shipping.Lambdas", """
                package com.acme.shipping;

                import java.util.ArrayList;
                import java.util.List;
                import java.util.function.Function;

                public class Lambdas {
                    public List<String> names(List<Rate> rates) {
                        List<String> out = new ArrayList<>();
                        Function<Rate, String> naming = rate -> "rate:" + rate.cents();
                        for (Rate rate : rates) {
                            out.add(naming.apply(rate));
                        }
                        return out;
                    }

                    public boolean isRate(Object value) {
                        return value instanceof Rate;
                    }

                    public Class<?> rateClass() {
                        return Rate.class;
                    }

                    public String guarded(Object value) {
                        try {
                            return value.toString();
                        } catch (IllegalStateException e) {
                            return "boom";
                        }
                    }
                }
                """);

        sources.put("com.acme.shipping.Comparables", """
                package com.acme.shipping;

                public class Comparables implements Comparable<Comparables> {
                    private final int rank;

                    public Comparables(int rank) {
                        this.rank = rank;
                    }

                    @Override
                    public int compareTo(Comparables other) {
                        return Integer.compare(rank, other.rank);
                    }
                }
                """);

        sources.put("com.acme.shipping.AbstractBase", """
                package com.acme.shipping;

                import java.util.ArrayList;
                import java.util.List;

                public abstract class AbstractBase {
                    public static final List<String> ZONES = new ArrayList<>();

                    public abstract void mustImplement();

                    public native void goesNative();

                    public void concrete() {
                        mustImplement();
                    }
                }
                """);

        sources.put("com.acme.shipping.Trivia", """
                package com.acme.shipping;

                public final class Trivia {
                    private final String name;

                    public Trivia(String name) {
                        this.name = name;
                    }

                    public void empty() {
                    }

                    public int constant() {
                        return 7;
                    }

                    public String getName() {
                        return name;
                    }

                    public int realWork(int n) {
                        int total = 0;
                        for (int i = 0; i < n; i++) {
                            total += i * 3;
                        }
                        return total;
                    }
                }
                """);

        sources.put("com.acme.shipping.TrivialFailure", """
                package com.acme.shipping;

                public class TrivialFailure extends RuntimeException {
                    public TrivialFailure(String message) {
                        super(message);
                    }
                }
                """);

        sources.put("com.acme.shipping.TestOnlyHelper", """
                package com.acme.shipping;

                public final class TestOnlyHelper {
                    public static String help(int seed) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < seed; i++) {
                            sb.append(i);
                        }
                        return sb.toString();
                    }
                }
                """);

        sources.put("com.acme.shipping.CycleHelper", """
                package com.acme.shipping;

                public final class CycleHelper {
                    public static void step(int n) {
                        if (n > 0) {
                            RateSelectorTest.callback(n - 1);
                        }
                    }
                }
                """);

        sources.put("com.acme.shipping.RateSelectorTest", """
                package com.acme.shipping;

                import org.junit.jupiter.api.Test;

                public class RateSelectorTest {
                    @Test
                    public void picksCheapest() {
                        CycleHelper.step(2);
                        TestOnlyHelper.help(3);
                    }

                    public static void callback(int n) {
                        CycleHelper.step(n);
                    }
                }
                """);

        return sources;
    }

    private static String annotation(String packageName, String simpleName, String targets) {
        return "package " + packageName + ";\n\n"
                + "import java.lang.annotation.ElementType;\n"
                + "import java.lang.annotation.Retention;\n"
                + "import java.lang.annotation.RetentionPolicy;\n"
                + "import java.lang.annotation.Target;\n\n"
                + "@Retention(RetentionPolicy.RUNTIME)\n"
                + "@Target({" + targetList(targets) + "})\n"
                + "public @interface " + simpleName + " {\n"
                + "    String value() default \"\";\n"
                + "}\n";
    }

    private static String targetList(String targets) {
        StringBuilder sb = new StringBuilder();
        for (String target : targets.split(",")) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append("ElementType.").append(target.trim());
        }
        return sb.toString();
    }
}
