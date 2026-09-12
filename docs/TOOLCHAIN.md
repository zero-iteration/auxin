# Local toolchain (verified 2026-09-12)

| tool | version | status |
|---|---|---|
| JDK | Temurin 17.0.18 (**arm64**) | available |
| Maven | 3.9.15 | available |
| Python | 3.14.4 | available |
| Node | 25.9.0 | available |
| Gradle | — | NOT installed (use Maven) |
| Docker | — | NOT installed |
| kubectl | — | NOT installed |

## Constraints this imposes

1. **Agent must compile to Java 8 bytecode** (`maven.compiler.release=8`) so it can attach
   to host apps on JDK 8/11/17/21. We only have JDK 17 locally, so `--release 8` is the
   mechanism. We cannot locally test attach-to-JDK-8.

2. **BENCHMARKS RUN ON aarch64 (Apple M-series), PRODUCTION IS ALMOST CERTAINLY x86_64
   LINUX.** This is a real measurement hazard:
   - Apple M-series cache line is **128 bytes**; x86_64 is **64 bytes**. False-sharing
     behaviour and any padding decisions DO NOT transfer directly.
   - `System.nanoTime()` cost differs: Linux x86_64 uses vDSO `clock_gettime` with TSC;
     macOS/arm64 uses `mach_absolute_time`. Different cost profile.
   - Branch predictor and memory ordering (ARM is weakly ordered, x86 is TSO) differ.
   => **Every JMH number produced locally is INDICATIVE ONLY.** The latency gate must be
      re-run on x86_64 Linux before any production claim. Record both or mark as provisional.

3. **No Docker / kubectl** => the `deploy/` module (Kyverno policy, helm) can be written
   and statically validated but NOT executed locally. Integration testing of pod injection
   is deferred and must be flagged as unverified.

4. No ClickHouse locally => `gt-store` SQLite adapter is the one we can actually exercise.
   The ClickHouse adapter ships behind the same port but is untested until there's a server.
