package com.astrovm.crosstune;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class NativeWhisper {
    private native long nativeOpen(String model);
    private native byte[] nativeWords(long instance, float[] samples, String language, int threads);
    private native void nativeClose(long instance);

    public static void main(String[] args) {
        System.load(args[0]);
        NativeWhisper bridge = new NativeWhisper();
        long instance = bridge.nativeOpen("fake");
        if (instance == 0) throw new AssertionError("fake context did not open");
        try {
            // Reuse the context and inputs to exercise cleanup after both decoder outcomes.
            float[] samples = {Float.parseFloat(args[1])};
            for (int i = 0; i < 100; i++) {
                byte[] actual = bridge.nativeWords(instance, samples, i % 2 == 0 ? "en" : null, 1);
                byte[] expected = switch (args[1]) {
                    case "-1", "2" -> null;
                    case "0" -> new byte[0];
                    case "1" -> "120\t hello".getBytes(StandardCharsets.UTF_8);
                    default -> throw new AssertionError("unknown outcome");
                };
                if (!Arrays.equals(actual, expected)) {
                    throw new AssertionError("outcome " + args[1] + ": expected " + Arrays.toString(expected)
                        + ", got " + Arrays.toString(actual));
                }
            }
        } finally {
            bridge.nativeClose(instance);
        }
    }
}
