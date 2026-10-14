package com.gtnewhorizons.retrofuturabootstrap;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.CRC32;
import org.jetbrains.annotations.NotNull;

/**
 * Prepares (reads and transforms) classes on a background thread ahead of the thread that loads them, using the order
 * the classes were defined in during the previous launch. The loading thread then only defines the prepared bytes.
 * <p>
 * On the first launch, and after the mods, config or Java changed, it only records the order. Rules that keep the
 * result identical to normal loading:
 * <ul>
 *     <li>Transformers prepare with a lock, so they don't intercept eachother.
 *     <li>Only starts when the signature equals the previous launch.
 *     <li>Transformers like AE2's integration depend on state setup later, so prepared bytes are used ONLY if they are
 *     identical to previous launch.
 *     <li>If the Java version, configs or mods changed, the worker does not run (obviously).
 * </ul>
 */
public final class ClassPreloader<P extends ClassPreloader.Prepared> {
    public interface Prepared {
        String key();
        byte[] bytes();
    }

    public interface Target<P> {
        P prepare(String name);
        String signature();
        boolean tryLockTransforms();
        void unlockTransforms();
    }

    private static final String HEADER = "# RFB class preload order v4";

    private static final int LOOKAHEAD = Integer.getInteger("rfb.preloadLookahead", 4000);
    /**
     * Classes whose (untransformed) bytes contain one of these strings are never prepared off-thread: transformers
     * acting on them have side effects that depend on when they run. AE2's ASMIntegration (which looks for exactly
     * this annotation) initializes AE2 integration modules on first use, permanently failing them if that's too early.
     */
    private static final byte[][] SKIP_CONSTANTS = parseSkipConstants(
            System.getProperty("rfb.preloadSkipConstants", "appeng/transformer/annotations/Integration"));

    private final Target<P> target;
    private final File orderFile;

    // previous launch
    private final String[] order;
    private final int[] orderLength;
    private final long[] orderCrc;
    private final Map<String, Integer> orderIndex = new HashMap<>();
    private final String expectedGate;
    private final String expectedFingerprint;
    private final FutureTask<String> fingerprintNow = new FutureTask<>(ClassPreloader::fingerprint);

    // this launch
    private final List<String> recorded = new ArrayList<>(70000);

    private final Map<String, Entry<P>> prepared = new ConcurrentHashMap<>();
    private final Object signal = new Object();
    /** The thread that created the loader: the game's main thread */
    private final Thread mainThread = Thread.currentThread();

    private volatile Thread workerThread;
    private volatile boolean workerWaiting;
    private volatile boolean tainted;
    private volatile int mainPos = -1;
    private volatile boolean started;
    private boolean refused;
    private String lastSignature;

    private static final class Entry<P> {
        final P payload;
        final String signature;
        final int position;

        Entry(P payload, String signature, int position) {
            this.payload = payload;
            this.signature = signature;
            this.position = position;
        }
    }

    public ClassPreloader(Target<P> target, File orderFile) {
        this.target = target;
        this.orderFile = orderFile;
        List<String> names = null;
        List<Integer> lengths = null;
        List<Long> crcs = null;
        String gate = null;
        String fingerprint = null;
        if (orderFile.isFile()) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(Files.newInputStream(orderFile.toPath()), StandardCharsets.UTF_8))) {
                if (HEADER.equals(reader.readLine())) {
                    gate = value(reader.readLine(), "gate ");
                    fingerprint = value(reader.readLine(), "fingerprint ");
                    names = new ArrayList<>(70000);
                    lengths = new ArrayList<>(70000);
                    crcs = new ArrayList<>(70000);
                    String line;
                    while ((line = reader.readLine()) != null) {
                        final String[] parts = line.split("\t");
                        if (parts.length == 3) {
                            names.add(parts[0]);
                            lengths.add(Integer.parseInt(parts[1]));
                            crcs.add(Long.parseLong(parts[2], 16));
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                Main.logger.warn("Could not read the class preload order from {}", orderFile, e);
                gate = null;
            }
        }
        if (gate != null && fingerprint != null) {
            order = names.toArray(new String[0]);
            orderLength = new int[order.length];
            orderCrc = new long[order.length];
            for (int i = order.length - 1; i >= 0; i--) {
                orderIndex.put(order[i], i);
                orderLength[i] = lengths.get(i);
                orderCrc[i] = crcs.get(i);
            }
        } else {
            order = null;
            orderLength = null;
            orderCrc = null;
        }
        expectedGate = gate;
        expectedFingerprint = fingerprint;
        if (order != null) {
            final Thread t = new Thread(fingerprintNow, "RFB-Preload fingerprint");
            t.setDaemon(true);
            t.start();
        }
        Main.logger.info(
                "Class preloader: {} classes from the previous launch, gate {}",
                order == null ? 0 : order.length,
                expectedGate);
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "RFB-Preload shutdown"));
    }

    private static String value(String line, String prefix) {
        return line != null && line.startsWith(prefix) ? line.substring(prefix.length()) : null;
    }

    public void transformLockReleased() {
        final Thread worker = workerThread;
        if (workerWaiting && worker != null && worker != Thread.currentThread()) {
            LockSupport.unpark(worker);
        }
    }

    public boolean isWorkerThread() {
        return Thread.currentThread() == workerThread;
    }

    /**
     * The worker's nested load can't be served safely: the class being prepared is discarded, because a transformer
     * saw a ClassNotFoundException the loading thread wouldn't see.
     */
    public void taint() {
        tainted = true;
    }

    /** Whether these are the exact bytes the class was defined with in the previous launch. */
    public boolean matchesLastLaunch(String name, byte[] bytes) {
        final Integer position = orderIndex.get(name);
        if (position == null || bytes.length != orderLength[position]) {
            return false;
        }
        final CRC32 crc = new CRC32();
        crc.update(bytes, 0, bytes.length);
        return crc.getValue() == orderCrc[position];
    }

    /** Whether the class (by its untransformed bytes) must only be transformed by the thread that loads it. */
    public boolean mustNotPreload(byte[] untransformed) {
        for (byte[] constant : SKIP_CONSTANTS) {
            if (contains(untransformed, constant)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0, last = haystack.length - needle.length; i <= last; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static byte[][] parseSkipConstants(String list) {
        final List<byte[]> result = new ArrayList<>();
        for (String s : list.split(",")) {
            if (!s.trim().isEmpty()) {
                result.add(s.trim().getBytes(StandardCharsets.UTF_8));
            }
        }
        return result.toArray(new byte[0][]);
    }

    /** Returns the prepared class for this key if it's still valid, removing it. */
    public P take(String key) {
        if (!started) {
            return null;
        }
        final Entry<P> entry = prepared.remove(key);
        if (entry == null) {
            return null;
        }
        return entry.signature.equals(target.signature()) ? entry.payload : null;
    }

    /** Called after the loader defined a class, on the defining thread. */
    public void onDefined(String name, byte[] bytes) {
        final CRC32 crc = new CRC32();
        crc.update(bytes, 0, bytes.length);
        final String crcHex = Long.toHexString(crc.getValue());
        synchronized (recorded) {
            recorded.add(name + '\t' + bytes.length + '\t' + crcHex);
        }
        if (Thread.currentThread() != mainThread) {
            return;
        }
        if (!started && !refused && order != null) {
            final String signature = target.signature();
            if (!signature.equals(lastSignature)) {
                lastSignature = signature;
                Main.logger.debug(
                        "Class preloader: setup is now {} (start at {}), at {}", signature, expectedGate, name);
            }
            if (expectedGate.equals(signature)) {
                String now;
                try {
                    now = fingerprintNow.get();
                } catch (Exception e) {
                    now = "failed: " + e;
                }
                if (now.equals(expectedFingerprint)) {
                    start(name);
                } else {
                    refused = true;
                    Main.logger.info(
                            "Class preloader: mods, config or Java changed since the last launch ({} -> {}), not preloading this time",
                            expectedFingerprint,
                            now);
                }
            }
        }
        if (started) {
            final Integer position = orderIndex.get(name);
            if (position != null && position > mainPos) {
                mainPos = position;
                synchronized (signal) {
                    signal.notifyAll();
                }
            }
        }
    }

    private void start(String atClass) {
        started = true;
        final Integer position = orderIndex.get(atClass);
        mainPos = position == null ? -1 : position;
        Main.logger.info("Class preloader started at {} (position {} of {})", atClass, mainPos, order.length);
        final Thread worker = new Thread(this::work, "RFB-Preload");
        worker.setDaemon(true);
        // normal priority: a descheduled worker holding the transformer lock would stall threads waiting for it
        worker.setContextClassLoader(mainThread.getContextClassLoader());
        workerThread = worker;
        worker.start();
    }

    private void work() {
        int cursor = mainPos + 1;
        int sincePurge = 0;
        try {
            while (cursor < order.length) {
                synchronized (signal) {
                    while (cursor > mainPos + LOOKAHEAD) {
                        signal.wait(100);
                    }
                }
                if (cursor <= mainPos) {
                    cursor = mainPos + 1;
                    continue;
                }
                final int position = cursor++;
                if (!target.tryLockTransforms()) {
                    while (true) {
                        workerWaiting = true;
                        if (target.tryLockTransforms()) {
                            break;
                        }
                        LockSupport.parkNanos(this, 2_000_000L);
                        workerWaiting = false;
                    }
                    workerWaiting = false;
                }
                P payload;
                String signature;
                try {
                    tainted = false;
                    payload = target.prepare(order[position]);
                    signature = payload == null ? null : target.signature();
                } finally {
                    target.unlockTransforms();
                }
                if (payload != null && !tainted) {
                    final byte[] bytes = payload.bytes();
                    final CRC32 crc = new CRC32();
                    crc.update(bytes, 0, bytes.length);
                    if (bytes.length == orderLength[position] && crc.getValue() == orderCrc[position]) {
                        prepared.put(payload.key(), new Entry<>(payload, signature, position));
                    }
                }
                if (++sincePurge >= 1000) {
                    sincePurge = 0;
                    final int oldest = mainPos - LOOKAHEAD;
                    prepared.values().removeIf(e -> e.position < oldest);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            Main.logger.warn("Class preloader stopped", t);
        }
    }

    private void shutdown() {
        final List<String> lines;
        synchronized (recorded) {
            lines = new ArrayList<>(recorded);
        }
        // not under the loader lock: a System.exit from a thread holding it would hang here otherwise
        write(orderFile, HEADER + "\ngate " + target.signature() + "\nfingerprint " + fingerprint(), lines);
    }

    /** Java version, and path/size/modification time of everything in mods/ and config/ */
    private static String fingerprint() {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(System.getProperty("java.version", "").getBytes(StandardCharsets.UTF_8));
            for (String dir : new String[] {"mods", "config"}) {
                final Path root = new File(dir).toPath();
                if (!Files.isDirectory(root)) {
                    continue;
                }
                final List<String> entries = new ArrayList<>();
                Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                    @Override
                    public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) {
                        entries.add(root.relativize(file) + "|" + attrs.size() + "|"
                                + attrs.lastModifiedTime().toMillis());
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public @NotNull FileVisitResult visitFileFailed(@NotNull Path file, @NotNull IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
                Collections.sort(entries);
                for (String entry : entries) {
                    digest.update(entry.getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) '\n');
                }
            }
            final StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "failed: " + e;
        }
    }

    private static void write(File file, String header, List<String> lines) {
        final File tmp = new File(file.getPath() + ".tmp");
        try (Writer writer = new BufferedWriter(
                new OutputStreamWriter(Files.newOutputStream(tmp.toPath()), StandardCharsets.UTF_8))) {
            writer.write(header);
            writer.write('\n');
            for (String line : lines) {
                writer.write(line);
                writer.write('\n');
            }
        } catch (IOException e) {
            Main.logger.warn("Could not write {}", file, e);
            return;
        }
        if (!(file.delete() || !file.exists()) || !tmp.renameTo(file)) {
            Main.logger.warn("Could not replace {}", file);
        }
    }
}
