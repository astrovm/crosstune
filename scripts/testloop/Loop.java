import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Runs test specs ("Class" or "Class#method") again and again in one JVM.
 * Args: rounds seed spec... . A seed above 0 shuffles the order of the specs each round.
 * Only specs marked with a leading "!" are counted; the others run before them as warm-up and state.
 */
public class Loop {
    public static void main(String[] args) throws Exception {
        int rounds = Integer.parseInt(args[0]);
        long seed = Long.parseLong(args[1]);
        List<String> specs = new ArrayList<>();
        for (int i = 2; i < args.length; i++) specs.add(args[i]);
        Random random = new Random(seed);
        int counted = 0, failed = 0;
        for (int round = 1; round <= rounds; round++) {
            List<String> order = new ArrayList<>(specs);
            if (seed > 0) Collections.shuffle(order, random);
            for (String raw : order) {
                boolean count = raw.startsWith("!");
                String spec = count ? raw.substring(1) : raw;
                String[] parts = spec.split("#");
                Class<?> type = Class.forName("com.astrovm.crosstune." + parts[0]);
                Request request = parts.length > 1 ? Request.method(type, parts[1]) : Request.aClass(type);
                JUnitCore core = new JUnitCore();
                long start = System.nanoTime();
                Result result = core.run(request);
                long ms = (System.nanoTime() - start) / 1_000_000;
                if (!count && result.wasSuccessful()) continue;
                if (count) counted++;
                if (!result.wasSuccessful() && count) failed++;
                System.out.println("LOOP round " + round + " " + spec + " " + (result.wasSuccessful() ? "PASS" : "FAIL") + " " + ms + "ms" + (count ? "" : " (warm-up)"));
                for (Failure f : result.getFailures()) {
                    System.out.println("LOOP-FAILURE " + f.getDescription().getDisplayName());
                    String trace = f.getTrace();
                    System.out.println(trace.length() > 12000 ? trace.substring(0, 12000) : trace);
                }
            }
        }
        System.out.println("LOOP-SUMMARY counted " + counted + " failed " + failed);
        System.exit(0);
    }
}
