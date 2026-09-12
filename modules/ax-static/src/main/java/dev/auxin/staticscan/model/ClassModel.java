package dev.auxin.staticscan.model;

import org.objectweb.asm.Opcodes;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One class as read out of a class file.
 *
 * <p>WHY the origin path is carried alongside the bytecode facts: test detection (C50) needs to know
 * whether the class came from {@code src/test} or {@code test-classes}, and that is the one thing
 * about a class that is not in the class file. Losing it would force test detection back onto naming
 * heuristics alone, which Sensenmann itself describes as unsolved in the ambiguous cases.
 */
public final class ClassModel {

    private final String name;
    private final String internalName;
    private final String sourceFile;
    private final int access;
    private final String superName;
    private final List<String> interfaceNames;
    private final Set<String> annotationDescriptors;
    private final List<MethodModel> methods;
    private final String originPath;

    public ClassModel(String name,
                      String internalName,
                      String sourceFile,
                      int access,
                      String superName,
                      List<String> interfaceNames,
                      Set<String> annotationDescriptors,
                      List<MethodModel> methods,
                      String originPath) {
        this.name = name;
        this.internalName = internalName;
        this.sourceFile = sourceFile == null ? "" : sourceFile;
        this.access = access;
        this.superName = superName;
        this.interfaceNames = List.copyOf(interfaceNames);
        this.annotationDescriptors =
                Collections.unmodifiableSet(new LinkedHashSet<>(annotationDescriptors));
        this.methods = List.copyOf(methods);
        this.originPath = originPath == null ? "" : originPath;
    }

    /** Binary name in dotted form, e.g. {@code com.acme.shipping.RateSelector}. */
    public String name() {
        return name;
    }

    /** Internal name in slashed form, as it appears in the constant pool. */
    public String internalName() {
        return internalName;
    }

    /** {@code SourceFile} attribute value, or {@code ""} when compiled without debug info. */
    public String sourceFile() {
        return sourceFile;
    }

    public int access() {
        return access;
    }

    /** Dotted superclass name; {@code null} only for {@code java.lang.Object}. */
    public String superName() {
        return superName;
    }

    /** Dotted names of directly implemented interfaces. */
    public List<String> interfaceNames() {
        return interfaceNames;
    }

    public Set<String> annotationDescriptors() {
        return annotationDescriptors;
    }

    /** Every declared method, including ones that will get no probe. */
    public List<MethodModel> methods() {
        return methods;
    }

    /** Jar entry name or file path the class was read from. Used only for test detection. */
    public String originPath() {
        return originPath;
    }

    public boolean isPublic() {
        return (access & Opcodes.ACC_PUBLIC) != 0;
    }

    public boolean isInterface() {
        return (access & Opcodes.ACC_INTERFACE) != 0;
    }

    /** Package in dotted form, or {@code ""} for the unnamed package. */
    public String packageName() {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(0, dot);
    }

    /** Simple name, with any enclosing-class prefix stripped. */
    public String simpleName() {
        String withoutPackage = name.substring(name.lastIndexOf('.') + 1);
        int dollar = withoutPackage.lastIndexOf('$');
        return dollar < 0 ? withoutPackage : withoutPackage.substring(dollar + 1);
    }

    @Override
    public String toString() {
        return name;
    }
}
