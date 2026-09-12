package ax.bench.g4;

import ax.bench.agent.BenchAgent;

import java.lang.management.ManagementFactory;

/** Loads every generated class and reports the transform cost as one machine-readable line. */
public final class G4Child {

    public static void main(String[] args) throws Exception {
        int n = Integer.parseInt(args[0]);
        com.sun.management.OperatingSystemMXBean os =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        long cpu0 = os.getProcessCpuTime();
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            Class.forName(ClassGen.PKG.replace('/', '.') + ".C" + i, true, G4Child.class.getClassLoader());
        }
        long wall = System.nanoTime() - t0;
        long cpu = os.getProcessCpuTime() - cpu0;

        System.out.println("GT_G4"
            + " loadWallMs=" + fmt(wall)
            + " loadCpuMs=" + fmt(cpu)
            + " jvmUptimeMs=" + ManagementFactory.getRuntimeMXBean().getUptime()
            + " jvmCpuMs=" + fmt(os.getProcessCpuTime())
            + " classesSeen=" + BenchAgent.classesSeen.get()
            + " classesTransformed=" + BenchAgent.classesTransformed.get()
            + " probes=" + BenchAgent.probesInstalled.get()
            + " bytesIn=" + BenchAgent.bytesIn.get()
            + " bytesOut=" + BenchAgent.bytesOut.get()
            + " failures=" + BenchAgent.transformFailures.get());
    }

    static String fmt(long ns) { return String.format("%.1f", ns / 1e6); }
}
