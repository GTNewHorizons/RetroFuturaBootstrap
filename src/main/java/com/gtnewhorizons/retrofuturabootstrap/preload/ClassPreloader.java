package com.gtnewhorizons.retrofuturabootstrap.preload;

import com.gtnewhorizons.retrofuturabootstrap.Main;
import com.gtnewhorizons.retrofuturabootstrap.SharedConfig;
import com.gtnewhorizons.retrofuturabootstrap.concurrency.ParallelLoadingSafety;
import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.Logger;

/**
 * Loads and transforms the classes the last startups needed on worker threads, a little ahead of the main thread, so
 * the main thread finds them already defined. Classes are only loaded, never initialized, so no mod code runs off the
 * main thread. Needs {@code rfb.parallelClassLoading=true}.
 * <p>
 * The caller, which knows the game's startup, drives it: {@link #start} once every class transformer is registered,
 * {@link #reach} for each checkpoint on the way, {@link #finish} when startup is over. A class is never loaded before
 * the checkpoint it was first needed after, in case a transformer depends on something set up on the way there.
 */
public final class ClassPreloader {

    // looked up on use, the logger is set up after the loader starts defining classes
    static Logger log() {
        return Main.logger;
    }

    private static final int MAX_THREADS = 8;

    // workers may load classes recorded up to checkpoint; a class gets tagged with recordCheckpoint
    private static volatile int checkpoint = -1, recordCheckpoint = -1;
    private static volatile boolean stopped;
    private static volatile boolean recording;
    private static boolean started, finished;

    private static File listFile;
    private static List<PreloadList.Entry> list = Collections.emptyList();
    private static final AtomicInteger next = new AtomicInteger();
    private static final List<Thread> workers = new ArrayList<>();
    private static final List<PreloadList.Define> defines = new ArrayList<>();
    // launchwrapper classes live in another class loader, so the loader is only known by its supertypes here
    private static URLClassLoader loader;
    private static MethodHandle untransformName;
    private static long startNanos;

    private ClassPreloader() {}

    /**
     * Starts recording which classes get defined and, given a list from an earlier startup, preloading them.
     *
     * @param file where the list is kept between startups
     * @return whether classes are being preloaded
     */
    public static synchronized boolean start(File file) {
        if (started) return checkpoint >= 0 && !list.isEmpty();
        started = true;
        if (!SharedConfig.cfgParallelClassLoading || !(Main.launchLoader instanceof URLClassLoader)) return false;
        loader = (URLClassLoader) Main.launchLoader;
        listFile = file;
        startNanos = System.nanoTime();
        checkpoint = recordCheckpoint = 0;
        recording = true;
        list = PreloadList.read(listFile);
        if (list.isEmpty()) {
            log().info("No class preload list yet, recording this startup");
            return false;
        }
        final String unsafe = ParallelLoadingSafety.check(loader);
        if (unsafe != null) {
            log().warn("Class preloading disabled, {}", unsafe);
            list = Collections.emptyList();
            return false;
        }
        try {
            final Method untransform = loader.getClass().getDeclaredMethod("untransformName", String.class);
            untransform.setAccessible(true);
            untransformName = MethodHandles.lookup().unreflect(untransform).bindTo(loader);
            ParallelLoadingSafety.warmUp(loader);
            ParallelLoadingSafety.serializeRfbTransformers();
        } catch (Throwable t) {
            log().error("Class preloading disabled", t);
            list = Collections.emptyList();
            return false;
        }
        final int threads = threadCount();
        for (int i = 0; i < threads; i++) {
            final Thread worker = new Worker(i);
            workers.add(worker);
            worker.start();
        }
        log().info("Preloading {} classes on {} threads", list.size(), threads);
        return true;
    }

    /** Lets the workers load what was first needed up to this checkpoint; checkpoints only go up. */
    public static void reach(int newCheckpoint) {
        if (checkpoint < 0 || newCheckpoint <= checkpoint) return;
        recordCheckpoint = Math.max(recordCheckpoint, newCheckpoint);
        synchronized (ClassPreloader.class) {
            checkpoint = newCheckpoint;
            ClassPreloader.class.notifyAll();
        }
    }

    /**
     * Classes defined from now on are recorded as needed at this checkpoint at the earliest, before it is reached. For
     * a stretch whose classes depend on something set up during it.
     */
    public static void recordAtLeast(int laterCheckpoint) {
        if (checkpoint < 0) return;
        recordCheckpoint = Math.max(recordCheckpoint, laterCheckpoint);
    }

    /** @return the checkpoint the workers are allowed up to, -1 before start */
    public static int checkpoint() {
        return checkpoint;
    }

    /** Stops preloading and saves the list for the next startup on a background thread. */
    public static synchronized void finish() {
        if (finished || checkpoint < 0) return;
        finished = true;
        recording = false;
        synchronized (ClassPreloader.class) {
            stopped = true;
            ClassPreloader.class.notifyAll();
        }
        final long millis = (System.nanoTime() - startNanos) / 1_000_000;
        final Thread writer = new Thread(() -> saveList(millis), "RFB Class Preload Writer");
        writer.setDaemon(true);
        writer.start();
    }

    /** True on the preloader's own threads, whose lookups of missing classes are guesses. */
    public static boolean isPreloaderThread() {
        return Thread.currentThread() instanceof Worker;
    }

    /** Called by LaunchClassLoader for every class it defines. */
    public static void onDefine(String className) {
        if (!recording) return;
        final PreloadList.Define define =
                new PreloadList.Define(className, recordCheckpoint, Thread.currentThread() instanceof Worker);
        synchronized (defines) {
            defines.add(define);
        }
    }

    private static void saveList(long millis) {
        try {
            for (Thread worker : workers) worker.join(10_000);
            final List<PreloadList.Define> recorded;
            synchronized (defines) {
                recorded = new ArrayList<>(defines);
            }
            int loaded = 0, missing = 0, failed = 0;
            for (PreloadList.Entry e : list) {
                if (e.status == PreloadList.Entry.LOADED) loaded++;
                else if (e.status == PreloadList.Entry.MISSING) missing++;
                else if (e.status == PreloadList.Entry.FAILED) failed++;
            }
            final List<PreloadList.Entry> merged = PreloadList.merge(list, recorded);
            PreloadList.write(listFile, merged);
            log().info(
                            "Startup took {} ms after preloading started. Preloaded {} of {} classes ({} missing, {} failed), {} defined, saved {} for next time",
                            millis,
                            loaded,
                            list.size(),
                            missing,
                            failed,
                            recorded.size(),
                            merged.size());
        } catch (Throwable t) {
            log().error("Could not save the class preload list", t);
        }
    }

    private static int threadCount() {
        if (SharedConfig.cfgPreloadThreads > 0) return SharedConfig.cfgPreloadThreads;
        return Math.max(1, Math.min(MAX_THREADS, Runtime.getRuntime().availableProcessors() - 2));
    }

    private static boolean await(int wanted) throws InterruptedException {
        if (checkpoint >= wanted) return !stopped;
        synchronized (ClassPreloader.class) {
            while (checkpoint < wanted && !stopped) ClassPreloader.class.wait();
        }
        return !stopped;
    }

    private static void preload(PreloadList.Entry e) throws Throwable {
        final String path = ((String) untransformName.invokeExact(e.name)).replace('.', '/') + ".class";
        if (loader.findResource(path) == null) {
            e.status = PreloadList.Entry.MISSING;
            return;
        }
        try {
            Class.forName(e.name, false, loader);
            e.status = PreloadList.Entry.LOADED;
        } catch (VirtualMachineError err) {
            throw err;
        } catch (Throwable t) {
            e.status = PreloadList.Entry.FAILED;
            log().debug("Could not preload {}", e.name, t);
        }
    }

    private static final class Worker extends Thread {

        Worker(int index) {
            super("RFB Class Preloader #" + index);
            setDaemon(true);
            setContextClassLoader(loader);
        }

        @Override
        public void run() {
            try {
                int i;
                while ((i = next.getAndIncrement()) < list.size()) {
                    final PreloadList.Entry e = list.get(i);
                    if (!await(e.checkpoint)) return;
                    preload(e);
                }
            } catch (Throwable t) {
                stopped = true;
                log().error("Class preloading stopped", t);
            }
        }
    }
}
