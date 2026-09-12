package dev.auxin.staticscan.model;

import org.objectweb.asm.Opcodes;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One method as read out of a class file, before any manifest decision has been made about it.
 *
 * <p>WHY the raw {@code access} bitmask is kept rather than a pre-digested set of booleans: the skip
 * rule, the access string, the entry-point rule and the public-API rule each read different bits,
 * and re-deriving them from a lossy summary is how the four end up disagreeing.
 *
 * <p>Annotation <b>descriptors</b> are kept verbatim ({@code Lorg/springframework/.../GetMapping;})
 * and never resolved. Resolving them would mean modelling Spring, and a wrong model of Spring is
 * worse than an honest list of what was written on the method.
 */
public final class MethodModel {

    private final String name;
    private final String desc;
    private final int access;
    private final int line;
    private final Set<String> annotationDescriptors;
    private final MethodBodyShape bodyShape;

    public MethodModel(String name,
                       String desc,
                       int access,
                       int line,
                       Set<String> annotationDescriptors,
                       MethodBodyShape bodyShape) {
        this.name = name;
        this.desc = desc;
        this.access = access;
        this.line = line;
        this.annotationDescriptors =
                Collections.unmodifiableSet(new LinkedHashSet<>(annotationDescriptors));
        this.bodyShape = bodyShape;
    }

    public String name() {
        return name;
    }

    public String desc() {
        return desc;
    }

    public int access() {
        return access;
    }

    /** First source line of the body, or {@code -1} when the class carries no LineNumberTable. */
    public int line() {
        return line;
    }

    /** Both runtime-visible and runtime-invisible annotations; retention does not change intent. */
    public Set<String> annotationDescriptors() {
        return annotationDescriptors;
    }

    public MethodBodyShape bodyShape() {
        return bodyShape;
    }

    public String nameAndDesc() {
        return name + desc;
    }

    public boolean isPublic() {
        return (access & Opcodes.ACC_PUBLIC) != 0;
    }

    public boolean isStatic() {
        return (access & Opcodes.ACC_STATIC) != 0;
    }

    public boolean isSynthetic() {
        return (access & Opcodes.ACC_SYNTHETIC) != 0;
    }

    public boolean isBridge() {
        return (access & Opcodes.ACC_BRIDGE) != 0;
    }

    public boolean isAbstract() {
        return (access & Opcodes.ACC_ABSTRACT) != 0;
    }

    public boolean isNative() {
        return (access & Opcodes.ACC_NATIVE) != 0;
    }

    public boolean isConstructor() {
        return "<init>".equals(name);
    }

    public boolean isClassInitializer() {
        return "<clinit>".equals(name);
    }

    /**
     * The manifest's {@code access} token.
     *
     * <p>Lives here rather than in a helper class because the access bitmask is this object's own
     * state, and every other reading of it in the codebase already goes through this type.
     */
    public String accessString() {
        if ((access & Opcodes.ACC_PUBLIC) != 0) {
            return dev.auxin.manifest.MethodEntry.ACCESS_PUBLIC;
        }
        if ((access & Opcodes.ACC_PROTECTED) != 0) {
            return dev.auxin.manifest.MethodEntry.ACCESS_PROTECTED;
        }
        if ((access & Opcodes.ACC_PRIVATE) != 0) {
            return dev.auxin.manifest.MethodEntry.ACCESS_PRIVATE;
        }
        return dev.auxin.manifest.MethodEntry.ACCESS_PACKAGE_PRIVATE;
    }

    @Override
    public String toString() {
        return nameAndDesc();
    }
}
