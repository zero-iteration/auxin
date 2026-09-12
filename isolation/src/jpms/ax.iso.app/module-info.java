/**
 * A real named module, on the module path, resolved into the boot layer.
 *
 * <p>It exports {@code iso.app} (so a class-path driver can call it) but does NOT open it: no
 * deep reflection into this module is possible. The two derived variants built by
 * run-isolation.sh are {@code ax.iso.open} (exports + opens) and {@code ax.iso.sealed}
 * (neither) -- the three together bracket the whole JPMS encapsulation range.
 *
 * <p>Nothing here requires, reads or mentions the agent. That is the point: the agent plants a
 * reference into iso.app.Target at class-file-load time, and JPMS access control decides at
 * resolution time whether that reference is legal from inside this module.
 */
module ax.iso.app {
    exports iso.app;
}
