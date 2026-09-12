package dev.auxin.staticscan.entry;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The annotation descriptors that mark a method as externally invokable, and the {@code kind} each
 * one reports.
 *
 * <p>WHY descriptors and not resolved semantics: PLAN-v2 and the brief are explicit that we do not
 * model Spring. Deciding whether a {@code @Bean} method is actually registered would require
 * evaluating {@code @Conditional}, profiles, and the whole context lifecycle, and a <em>wrong</em>
 * model of that is far more dangerous than a list of what the author wrote. The kind is reported as
 * evidence; the consumer decides what to do with it.
 *
 * <p>Both {@code javax.} and {@code jakarta.} spellings are listed where the specification moved,
 * because the same application may contain both after a partial migration.
 */
public final class EntryPointCatalog {

    /** Kind reported for {@code public static void main(String[])}. */
    public static final String KIND_MAIN = "main";

    /** Kind reported for a class named in a {@code META-INF/services} file. */
    public static final String KIND_SERVICE_LOADER = "ServiceLoader";

    /** Descriptor of the one method signature the JVM itself will call without a caller. */
    public static final String MAIN_DESCRIPTOR = "([Ljava/lang/String;)V";

    private static final Map<String, String> KIND_BY_DESCRIPTOR = kindByDescriptor();

    /**
     * The subset that is meaningful on a type. Spring allows a type-level {@code @RequestMapping}
     * and JAX-RS a type-level {@code @Path}; the rest are declared {@code @Target(METHOD)} by their
     * own specifications, so looking for them on a class would only ever produce noise.
     */
    private static final Set<String> CLASS_LEVEL_DESCRIPTORS = Set.of(
            "Lorg/springframework/web/bind/annotation/RequestMapping;",
            "Ljavax/ws/rs/Path;",
            "Ljakarta/ws/rs/Path;");

    // Declared after CLASS_LEVEL_DESCRIPTORS on purpose: it is derived from it, and a static
    // initialiser reads the fields above it, not below.
    private static final Set<String> CLASS_LEVEL_KINDS = classLevelKinds();

    /** The {@code kind} for this annotation descriptor, or {@code null} if it marks nothing. */
    public String kindOf(String annotationDescriptor) {
        return KIND_BY_DESCRIPTOR.get(annotationDescriptor);
    }

    /** Whether this annotation, found on a class, marks that class's methods as entry points. */
    public boolean appliesAtClassLevel(String annotationDescriptor) {
        return CLASS_LEVEL_DESCRIPTORS.contains(annotationDescriptor);
    }

    /**
     * Whether a method could have acquired this {@code kind} by propagation from its type rather
     * than by carrying the annotation itself.
     *
     * <p>Exists so that a consumer choosing <em>one</em> kind to report for a method that has
     * several can prefer the one actually written on it. A {@code @Scheduled} method inside a
     * {@code @RequestMapping} class produces both kinds, and calling it an HTTP endpoint would be
     * the less true of the two answers.
     */
    public boolean isPropagatedFromType(String kind) {
        return CLASS_LEVEL_KINDS.contains(kind);
    }

    /** Derived from {@link #CLASS_LEVEL_DESCRIPTORS} so the two can never disagree. */
    private static Set<String> classLevelKinds() {
        Set<String> kinds = new LinkedHashSet<>();
        for (String descriptor : CLASS_LEVEL_DESCRIPTORS) {
            String kind = KIND_BY_DESCRIPTOR.get(descriptor);
            if (kind != null) {
                kinds.add(kind);
            }
        }
        return Set.copyOf(kinds);
    }

    private static Map<String, String> kindByDescriptor() {
        Map<String, String> kinds = new LinkedHashMap<>();

        // Spring MVC / WebFlux request mapping and its shorthands.
        kinds.put("Lorg/springframework/web/bind/annotation/RequestMapping;", "RequestMapping");
        kinds.put("Lorg/springframework/web/bind/annotation/GetMapping;", "GetMapping");
        kinds.put("Lorg/springframework/web/bind/annotation/PostMapping;", "PostMapping");
        kinds.put("Lorg/springframework/web/bind/annotation/PutMapping;", "PutMapping");
        kinds.put("Lorg/springframework/web/bind/annotation/DeleteMapping;", "DeleteMapping");
        kinds.put("Lorg/springframework/web/bind/annotation/PatchMapping;", "PatchMapping");

        // Invoked by a scheduler or an event multicaster: never by application bytecode.
        kinds.put("Lorg/springframework/scheduling/annotation/Scheduled;", "Scheduled");
        kinds.put("Lorg/springframework/context/event/EventListener;", "EventListener");

        // Message-driven: the caller is a broker client, outside the artifact entirely.
        kinds.put("Lorg/springframework/kafka/annotation/KafkaListener;", "KafkaListener");
        kinds.put("Lorg/springframework/amqp/rabbit/annotation/RabbitListener;", "RabbitListener");
        kinds.put("Lorg/springframework/jms/annotation/JmsListener;", "JmsListener");

        // Container lifecycle callbacks.
        kinds.put("Ljavax/annotation/PostConstruct;", "PostConstruct");
        kinds.put("Ljakarta/annotation/PostConstruct;", "PostConstruct");
        kinds.put("Ljavax/annotation/PreDestroy;", "PreDestroy");
        kinds.put("Ljakarta/annotation/PreDestroy;", "PreDestroy");

        // Factory methods the container calls reflectively.
        kinds.put("Lorg/springframework/context/annotation/Bean;", "Bean");

        // JAX-RS resources.
        kinds.put("Ljavax/ws/rs/Path;", "Path");
        kinds.put("Ljakarta/ws/rs/Path;", "Path");
        kinds.put("Ljavax/ws/rs/GET;", "GET");
        kinds.put("Ljakarta/ws/rs/GET;", "GET");
        kinds.put("Ljavax/ws/rs/POST;", "POST");
        kinds.put("Ljakarta/ws/rs/POST;", "POST");

        return Map.copyOf(kinds);
    }
}
