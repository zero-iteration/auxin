package io.auxin.agent.manifest;

import org.objectweb.asm.Opcodes;

/**
 * The probe-eligibility rule from CONTRACTS section 1, in ONE place, so that the agent and the
 * manifest generator can never disagree:
 *
 * <p><i>"Skip synthetic and bridge methods, {@code <clinit>}, and abstract/native. They get no
 * probe."</i>
 */
public final class Eligibility {

    public static boolean isProbeEligible(int access, String name) {
        if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return false;
        if ((access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0) return false;
        if ("<clinit>".equals(name)) return false;
        return true;
    }

    private Eligibility() { throw new AssertionError(); }
}
