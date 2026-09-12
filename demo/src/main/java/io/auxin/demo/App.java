package io.auxin.demo;

import io.auxin.demo.http.DemoServer;
import io.auxin.demo.repo.CustomerRepository;
import io.auxin.demo.repo.OrderRepository;
import io.auxin.demo.service.FraudService;
import io.auxin.demo.service.OrderService;
import io.auxin.demo.service.PricingService;
import io.auxin.demo.service.ShippingService;

/**
 * Entry point for the Auxin integration target.
 *
 * <p>Runs an in-process HTTP server, drives deterministic traffic against it for a fixed
 * duration (and a fixed minimum request count), then prints a machine-readable summary.
 *
 * <p>The point of this application is its <b>ground truth</b>: a documented set of methods
 * that are never executed, and a documented set that executes only on a rare branch. See
 * {@code demo/README.md} and {@code demo/ground-truth.json}. Tests assert against those
 * files, so changing what this class calls is a breaking change to the fixture.
 *
 * <pre>
 *   java -jar target/auxin-demo.jar --seconds=30 --min-requests=600 --threads=4
 * </pre>
 */
public final class App {

    private App() {
    }

    public static void main(String[] args) throws Exception {
        int seconds = intArg(args, "--seconds", 10);
        int minRequests = intArg(args, "--min-requests", 600);
        int maxRequests = intArg(args, "--max-requests", Integer.MAX_VALUE);
        int port = intArg(args, "--port", 0);
        int threads = intArg(args, "--threads", 4);

        OrderRepository orders = new OrderRepository();
        CustomerRepository customers = new CustomerRepository();
        OrderService orderService = new OrderService(
                orders, customers, new PricingService(), new ShippingService(), new FraudService());

        DemoServer server = new DemoServer(port, orderService);
        server.start();

        // GROUND TRUTH, "loaded but never invoked": MaintenanceWindow is loaded here and
        // nowhere else. Class.forName runs its <clinit> and nothing else — no constructor,
        // no instance method, no static method. That is the C10 distinction the collector
        // has to be able to make: a class can be LOADED and still have zero invoked
        // methods, which is not the same thing as a class that was never loaded at all.
        Class.forName("io.auxin.demo.service.MaintenanceWindow");

        String base = "http://127.0.0.1:" + server.port();
        System.out.println("[demo] listening on " + base
                + " seconds=" + seconds + " minRequests=" + minRequests + " threads=" + threads);

        TrafficDriver driver = new TrafficDriver(base, threads, seconds, minRequests, maxRequests);
        driver.run();

        server.stop();

        System.out.println("[demo] summary " + Counters.toJson());
        System.out.println("[demo] done");
    }

    private static int intArg(String[] args, String name, int fallback) {
        for (String a : args) {
            if (a.startsWith(name + "=")) {
                return Integer.parseInt(a.substring(name.length() + 1).trim());
            }
        }
        return fallback;
    }
}
