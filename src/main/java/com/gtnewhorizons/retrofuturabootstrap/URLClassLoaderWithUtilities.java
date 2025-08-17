package com.gtnewhorizons.retrofuturabootstrap;

import com.gtnewhorizons.retrofuturabootstrap.api.ClassNodeHandle;
import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformerHandle;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.net.URLStreamHandlerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Manifest;

/**
 * Non-Java-version-specific extensions to {@link URLClassLoaderBase}
 */
public class URLClassLoaderWithUtilities extends URLClassLoaderBase {
    public URLClassLoaderWithUtilities(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    public URLClassLoaderWithUtilities(URL[] urls) {
        super(urls);
    }

    public URLClassLoaderWithUtilities(URL[] urls, ClassLoader parent, URLStreamHandlerFactory factory) {
        super(urls, parent, factory);
    }

    public URLClassLoaderWithUtilities(String name, URL[] urls, ClassLoader parent) {
        super(name, urls, parent);
    }

    public URLClassLoaderWithUtilities(String name, URL[] urls, ClassLoader parent, URLStreamHandlerFactory factory) {
        super(name, urls, parent, factory);
    }

    private Map<String, Long> timings = new ConcurrentHashMap<>();
    public byte[] runRfbTransformers(
            final Collection<RfbClassTransformerHandle> rfbTransformers,
            final RfbClassTransformer.Context context,
            final Manifest manifest,
            final String className,
            byte[] basicClass) {
        if (rfbTransformers.isEmpty()) {
            return basicClass;
        }
        final ExtensibleClassLoader self = (ExtensibleClassLoader) this;
        int xformerIndex = 0;
        final ClassNodeHandle nodeHandle = new ClassNodeHandle(basicClass);
        byte[] previousBytes = basicClass;
        xformerLoop:
        for (RfbClassTransformerHandle handle : rfbTransformers) {
            for (final String exclusion : handle.exclusions()) {
                if (className.startsWith(exclusion)) {
                    continue xformerLoop;
                }
            }
            final RfbClassTransformer xformer = handle.transformer();
            try {
                if (xformer.shouldTransformClass(self, context, manifest, className, nodeHandle)) {
                    long start = System.nanoTime();
                    xformer.transformClass(self, context, manifest, className, nodeHandle);
                    long end = System.nanoTime();
                    long duration = end - start;

                    Thread currentThread = Thread.currentThread();
                    String threadName = currentThread.getName();
                    String side = determineSide(threadName);
                    String contextStr = context.name();

                    String transformerKey = handle.id();
                    String perClassKey = handle.id() + "|" + className;
                    String threadKey = handle.id() + "|thread:" + threadName;
                    String sideKey = handle.id() + "|side:" + side;
                    String contextKey = handle.id() + "|context:" + contextStr;
                    String perClassThreadKey = handle.id() + "|" + className + "|thread:" + threadName;
                    String perClassSideKey = handle.id() + "|" + className + "|side:" + side;
                    String perClassContextKey = handle.id() + "|" + className + "|context:" + contextStr;

                    timings.put(transformerKey, timings.getOrDefault(transformerKey, 0L) + duration);
                    timings.put(perClassKey, timings.getOrDefault(perClassKey, 0L) + duration);
                    timings.put(threadKey, timings.getOrDefault(threadKey, 0L) + duration);
                    timings.put(sideKey, timings.getOrDefault(sideKey, 0L) + duration);
                    timings.put(contextKey, timings.getOrDefault(contextKey, 0L) + duration);
                    timings.put(perClassThreadKey, timings.getOrDefault(perClassThreadKey, 0L) + duration);
                    timings.put(perClassSideKey, timings.getOrDefault(perClassSideKey, 0L) + duration);
                    timings.put(perClassContextKey, timings.getOrDefault(perClassContextKey, 0L) + duration);

                    if (Main.cfgDumpLoadedClassesPerTransformer) {
                        final byte[] newBytes = nodeHandle.computeBytes();
                        if (newBytes != null && !Arrays.equals(newBytes, previousBytes)) {
                            Main.dumpClass(
                                    this.getClassLoaderName(),
                                    String.format(
                                            "%s__S%03d_%s",
                                            className, xformerIndex, handle.id().replace(':', '$')),
                                    newBytes);
                        }
                        previousBytes = newBytes;
                    }
                }
            } catch (UnsupportedOperationException e) {
                if (e.getMessage().contains("requires ASM")) {
                    Main.logger.warn(
                            "ASM transformer {} encountered a newer classfile ({}) than supported: {}",
                            xformer.getClass().getName(),
                            className,
                            e.getMessage());
                    xformerIndex++;
                    continue;
                }
                throw e;
            }
            xformerIndex++;
        }
        return nodeHandle.computeBytes();
    }

    /**
     * Determines the logical side (client/server/unknown) based on thread name
     */
    private String determineSide(String threadName) {
        if (threadName == null) return "unknown";
        String lowerName = threadName.toLowerCase();
        if (lowerName.contains("server") || lowerName.contains("netty server")) {
            return "server";
        } else if (lowerName.contains("client") || lowerName.contains("main") || lowerName.contains("render")) {
            return "client";
        }
        return "unknown";
    }

    /**
     * Converts a time duration in nanoseconds to a human-readable format.
     * 
     * @param time The time duration in nanoseconds
     * @return A human-readable string representation of the time
     */
    private String formatTime(long time) {
        if (time < 1000) {
            return time + " ns";
        } else if (time < 1_000_000) {
            return String.format("%.2f μs", time / 1000.0);
        } else if (time < 1_000_000_000) {
            return String.format("%.2f ms", time / 1_000_000.0);
        } else {
            return String.format("%.3f s", time / 1_000_000_000.0);
        }
    }

    public void logTimings() {
        Map<String, Long> totalTimes = new HashMap<>();
        Map<String, Map<String, Long>> perClassTimes = new HashMap<>();
        Map<String, Map<String, Long>> threadTimes = new HashMap<>();
        Map<String, Map<String, Long>> sideTimes = new HashMap<>();
        Map<String, Map<String, Long>> contextTimes = new HashMap<>();
        Map<String, Map<String, Long>> detailedTimes = new HashMap<>();

        // Create defensive copy to avoid ConcurrentModificationException
        Map<String, Long> timingsCopy = new HashMap<>(timings);

        // Categorize timing data
        timingsCopy.forEach((key, time) -> {
            String[] parts = key.split("\\|");
            String transformer = parts[0];

            if (parts.length == 1) {
                // Total timing
                totalTimes.put(transformer, time);
            } else if (parts.length == 2) {
                String secondPart = parts[1];
                if (secondPart.startsWith("thread:")) {
                    // Thread timing
                    String threadName = secondPart.substring(7);
                    threadTimes.computeIfAbsent(transformer, k -> new HashMap<>()).put(threadName, time);
                } else if (secondPart.startsWith("side:")) {
                    // Side timing
                    String side = secondPart.substring(5);
                    sideTimes.computeIfAbsent(transformer, k -> new HashMap<>()).put(side, time);
                } else if (secondPart.startsWith("context:")) {
                    // Context timing
                    String context = secondPart.substring(8);
                    contextTimes.computeIfAbsent(transformer, k -> new HashMap<>()).put(context, time);
                } else {
                    // Per-class timing
                    perClassTimes.computeIfAbsent(transformer, k -> new HashMap<>()).put(secondPart, time);
                }
            } else if (parts.length >= 3) {
                // Detailed timing (class + thread/side/context)
                String className = parts[1];
                String context = parts[2];
                String detailKey = className + "|" + context;
                detailedTimes.computeIfAbsent(transformer, k -> new HashMap<>()).put(detailKey, time);
            }
        });

        // Write CSV files
        try {
            Path gameDir = Main.initialGameDir != null ? Main.initialGameDir.toPath() : Paths.get(".");
            Path csvDir = gameDir.resolve("csv");
            Files.createDirectories(csvDir);

            // Write total times CSV
            Path totalTimesFile = csvDir.resolve("rfb_transformer_total_times.csv");
            try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(totalTimesFile))) {
                writer.println("Transformer,Time (ns),Time (formatted)");
                totalTimes.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .forEach(entry -> writer.printf("%s,%d,%s%n", 
                        entry.getKey(), entry.getValue(), formatTime(entry.getValue())));
            }

            // Write per-class times CSV
            Path perClassTimesFile = csvDir.resolve("rfb_transformer_per_class_times.csv");
            try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(perClassTimesFile))) {
                writer.println("Transformer,Class,Time (ns),Time (formatted)");
                perClassTimes.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(transformerEntry -> {
                        String transformer = transformerEntry.getKey();
                        transformerEntry.getValue().entrySet().stream()
                            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                            .forEach(classEntry -> writer.printf("%s,%s,%d,%s%n",
                                transformer, classEntry.getKey(), classEntry.getValue(), formatTime(classEntry.getValue())));
                    });
            }

            // Write thread times CSV
            Path threadTimesFile = csvDir.resolve("rfb_transformer_thread_times.csv");
            try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(threadTimesFile))) {
                writer.println("Transformer,Thread,Time (ns),Time (formatted)");
                threadTimes.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(transformerEntry -> {
                        String transformer = transformerEntry.getKey();
                        transformerEntry.getValue().entrySet().stream()
                            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                            .forEach(threadEntry -> writer.printf("%s,%s,%d,%s%n",
                                transformer, threadEntry.getKey(), threadEntry.getValue(), formatTime(threadEntry.getValue())));
                    });
            }

            // Write side times CSV
            Path sideTimesFile = csvDir.resolve("rfb_transformer_side_times.csv");
            try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(sideTimesFile))) {
                writer.println("Transformer,Side,Time (ns),Time (formatted)");
                sideTimes.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(transformerEntry -> {
                        String transformer = transformerEntry.getKey();
                        transformerEntry.getValue().entrySet().stream()
                            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                            .forEach(sideEntry -> writer.printf("%s,%s,%d,%s%n",
                                transformer, sideEntry.getKey(), sideEntry.getValue(), formatTime(sideEntry.getValue())));
                    });
            }

            // Write context times CSV
            Path contextTimesFile = csvDir.resolve("rfb_transformer_context_times.csv");
            try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(contextTimesFile))) {
                writer.println("Transformer,Context,Time (ns),Time (formatted)");
                contextTimes.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(transformerEntry -> {
                        String transformer = transformerEntry.getKey();
                        transformerEntry.getValue().entrySet().stream()
                            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                            .forEach(contextEntry -> writer.printf("%s,%s,%d,%s%n",
                                transformer, contextEntry.getKey(), contextEntry.getValue(), formatTime(contextEntry.getValue())));
                    });
            }

            // Write detailed times CSV
            Path detailedTimesFile = csvDir.resolve("rfb_transformer_detailed_times.csv");
            try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(detailedTimesFile))) {
                writer.println("Transformer,Class,Context,Time (ns),Time (formatted)");
                detailedTimes.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(transformerEntry -> {
                        String transformer = transformerEntry.getKey();
                        transformerEntry.getValue().entrySet().stream()
                            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                            .forEach(detailEntry -> {
                                String[] detailParts = detailEntry.getKey().split("\\|", 2);
                                String className = detailParts[0];
                                String context = detailParts.length > 1 ? detailParts[1] : "";
                                writer.printf("%s,%s,%s,%d,%s%n",
                                    transformer, className, context, detailEntry.getValue(), formatTime(detailEntry.getValue()));
                            });
                    });
            }

            Main.logger.info("RFB timing data written to {}, {}, {}, {}, {}, {}", 
                totalTimesFile, perClassTimesFile, threadTimesFile, sideTimesFile, contextTimesFile, detailedTimesFile);
        } catch (IOException e) {
            Main.logger.warn("Failed to write RFB timing CSV files", e);
        }

        // Log total times only
        totalTimes.forEach((transformer, time) -> {
            Main.logger.info("RFB Transformer {} total time: {}", transformer, formatTime(time));
        });

        timings.clear();
    }
}
