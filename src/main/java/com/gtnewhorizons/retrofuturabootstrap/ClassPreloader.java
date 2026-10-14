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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *     <li>Each class is only prepared while the signature matches the previous launch.
 *     <li>Only signatures that lasted at least {@code rfb.preloadMinEpoch} classes, and only after FML's deobfuscation
 *     transformer is registered, are preloaded.
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

    private static final String HEADER = "# RFB class preload order v5";

    private static final int LOOKAHEAD = Integer.getInteger("rfb.preloadLookahead", 4000);
    /** Setups that lasted for fewer class definitions than this in the previous launch are not preloaded */
    private static final int MIN_EPOCH = Integer.getInteger("rfb.preloadMinEpoch", 300);

    private static final boolean PRELOAD_BEFORE_DEOBF = Boolean.getBoolean("rfb.preloadBeforeDeobf");
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
    private final int[] orderSetup;

    private final String[] orderSetups;
    private final int[] orderEpoch;
    private final boolean[] orderEligible;
    private final Set<String> eligibleSetups = new HashSet<>();

    private final Map<String, Integer> orderIndex = new HashMap<>();
    private final String expectedFingerprint;
    private final FutureTask<String> fingerprintNow = new FutureTask<>(ClassPreloader::fingerprint);

    // this launch
    private final List<String> recorded = new ArrayList<>(70000);
    private final Map<String, Integer> setupIds = new HashMap<>();
    private final List<String> setups = new ArrayList<>();

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
    private String lastMainSignature;

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
        List<Integer> setupRefs = null;
        Map<Integer, String> setupTable = new HashMap<>();
        String fingerprint = null;
        if (orderFile.isFile()) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(Files.newInputStream(orderFile.toPath()), StandardCharsets.UTF_8))) {
                if (HEADER.equals(reader.readLine())) {
                    fingerprint = value(reader.readLine(), "fingerprint ");
                    names = new ArrayList<>(70000);
                    lengths = new ArrayList<>(70000);
                    crcs = new ArrayList<>(70000);
                    setupRefs = new ArrayList<>(70000);
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("setup ")) {
                            final int space = line.indexOf(' ', 6);
                            setupTable.put(Integer.parseInt(line.substring(6, space)), line.substring(space + 1));
                            continue;
                        }
                        final String[] parts = line.split("\t");
                        if (parts.length == 4) {
                            names.add(parts[0]);
                            lengths.add(Integer.parseInt(parts[1]));
                            crcs.add(Long.parseLong(parts[2], 16));
                            setupRefs.add(Integer.parseInt(parts[3]));
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                Main.logger.warn("Could not read the class preload order from {}", orderFile, e);
                fingerprint = null;
            }
        }
        if (fingerprint != null && names != null && !names.isEmpty()) {
            final int n = names.size();
            order = names.toArray(new String[0]);
            orderLength = new int[n];
            orderCrc = new long[n];
            orderSetup = new int[n];
            orderEpoch = new int[n];
            orderEligible = new boolean[n];
            int maxSetup = 0;
            for (int ref : setupRefs) {
                maxSetup = Math.max(maxSetup, ref);
            }
            orderSetups = new String[maxSetup + 1];
            for (Map.Entry<Integer, String> e : setupTable.entrySet()) {
                if (e.getKey() <= maxSetup) {
                    orderSetups[e.getKey()] = e.getValue();
                }
            }
            for (int i = n - 1; i >= 0; i--) {
                orderIndex.put(order[i], i);
                orderLength[i] = lengths.get(i);
                orderCrc[i] = crcs.get(i);
                orderSetup[i] = setupRefs.get(i);
            }
            // runs of equal setups
            int epoch = 0;
            int epochStart = 0;
            for (int i = 1; i <= n; i++) {
                if (i == n || orderSetup[i] != orderSetup[i - 1]) {
                    final String setup = orderSetups[orderSetup[epochStart]];
                    final boolean eligible = i - epochStart >= MIN_EPOCH && setup != null && afterDeobfSetup(setup);
                    for (int j = epochStart; j < i; j++) {
                        orderEpoch[j] = epoch;
                        orderEligible[j] = eligible;
                    }
                    if (eligible) {
                        eligibleSetups.add(orderSetups[orderSetup[epochStart]]);
                    }
                    epoch++;
                    epochStart = i;
                }
            }
        } else {
            order = null;
            orderLength = null;
            orderCrc = null;
            orderSetup = null;
            orderSetups = null;
            orderEpoch = null;
            orderEligible = null;
        }
        expectedFingerprint = fingerprint;
        if (order != null) {
            final Thread t = new Thread(fingerprintNow, "RFB-Preload fingerprint");
            t.setDaemon(true);
            t.start();
        }
        Main.logger.info(
                "Class preloader: {} classes from the previous launch, preloading in setups {}",
                order == null ? 0 : order.length,
                eligibleSetups);
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "RFB-Preload shutdown"));
    }

    private static String value(String line, String prefix) {
        return line != null && line.startsWith(prefix) ? line.substring(prefix.length()) : null;
    }

    /**
     * Whether the setup is past FML's deobfuscation setup (the rename transformer is registered: the third field of
     * the signature). Before that, coremods and tweakers are still being set up, changing state the transformers read
     * that the setup description doesn't capture. {@code -Drfb.preloadBeforeDeobf=true} allows those setups too.
     */
    private static boolean afterDeobfSetup(String setup) {
        if (PRELOAD_BEFORE_DEOBF) {
            return true;
        }
        final String[] parts = setup.split("/", 4);
        return parts.length > 2 && parts[2].equals("true");
    }

    /** The transformer lock was released: wake the worker if it waits for it. */
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
        final String signature = target.signature();
        synchronized (recorded) {
            Integer setup = setupIds.get(signature);
            if (setup == null) {
                setup = setups.size();
                setups.add(signature);
                setupIds.put(signature, setup);
            }
            recorded.add(name + '\t' + bytes.length + '\t' + crcHex + '\t' + setup);
        }
        if (Thread.currentThread() != mainThread || order == null) {
            return;
        }
        final Integer position = orderIndex.get(name);
        boolean wake = false;
        if (position != null && position > mainPos) {
            mainPos = position;
            wake = true;
        }
        if (!signature.equals(lastMainSignature)) {
            lastMainSignature = signature;
            wake = true;
            Main.logger.debug("Class preloader: setup is now {}, at {}", signature, name);
        }
        if (!started && !refused && eligibleSetups.contains(signature)) {
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
        if (started && wake) {
            synchronized (signal) {
                signal.notifyAll();
            }
        }
    }

    private void start(String atClass) {
        started = true;
        Main.logger.info(
                "Class preloader started at {} (position {} of {}), setup {}",
                atClass,
                mainPos,
                order.length,
                lastMainSignature);
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
                final int main = mainPos;
                if (cursor <= main) {
                    cursor = main + 1;
                    continue;
                }
                if (!orderEligible[cursor]) {
                    cursor++;
                    continue;
                }
                final String wanted = orderSetups[orderSetup[cursor]];
                if (!wanted.equals(target.signature())) {
                    if (orderEpoch[cursor] <= orderEpoch[Math.max(main, 0)]) {
                        // the main thread has reached (or passed) this class's setup period, with a different setup
                        cursor++;
                    } else {
                        // a later setup: wait until the main thread moves on or the setup changes
                        synchronized (signal) {
                            if (mainPos == main) {
                                signal.wait(20);
                            }
                        }
                    }
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
                P payload = null;
                boolean changed = false;
                try {
                    // the setup may have changed while waiting for the lock
                    if (wanted.equals(target.signature())) {
                        tainted = false;
                        payload = target.prepare(order[position]);
                        changed = payload != null && !wanted.equals(target.signature());
                    }
                } finally {
                    target.unlockTransforms();
                }
                if (payload != null && !changed && !tainted) {
                    final byte[] bytes = payload.bytes();
                    final CRC32 crc = new CRC32();
                    crc.update(bytes, 0, bytes.length);
                    if (bytes.length == orderLength[position] && crc.getValue() == orderCrc[position]) {
                        prepared.put(payload.key(), new Entry<>(payload, wanted, position));
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
        final List<String> setupLines = new ArrayList<>();
        synchronized (recorded) {
            lines = new ArrayList<>(recorded);
            for (int i = 0; i < setups.size(); i++) {
                setupLines.add("setup " + i + ' ' + setups.get(i));
            }
        }
        // not under the loader lock: a System.exit from a thread holding it would hang here otherwise
        setupLines.addAll(lines);
        write(orderFile, HEADER + "\nfingerprint " + fingerprint(), setupLines);
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
