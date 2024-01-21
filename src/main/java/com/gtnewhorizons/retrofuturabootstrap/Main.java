package com.gtnewhorizons.retrofuturabootstrap;

import com.gtnewhorizons.retrofuturabootstrap.api.SimpleClassTransformer;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

public class Main {
    /** The compatibility ClassLoader that's a parent of LaunchClassLoader. */
    public static SimpleTransformingClassLoader compatLoader;
    /** An ArrayList of all compatibility class transformers used, mutable, in order of application */
    private static final AtomicReference<@NotNull SimpleClassTransformer[]> compatibilityTransformers =
            new AtomicReference<>(new SimpleClassTransformer[0]);
    /** The ClassLoader that loaded this class. */
    public static final ClassLoader appClassLoader = Main.class.getClassLoader();

    /** Get the system property {@code propName} value as a boolean, or default to {@code defaultValue} if not present */
    private static boolean getBooleanOr(final String propName, final boolean defaultValue) {
        final String propValue = System.getProperty(propName);
        if (propValue == null) {
            return defaultValue;
        }
        try {
            return Boolean.parseBoolean(propValue);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /** Returns the running version of RFB */
    public static String getVersion() {
        return BuildConfig.VERSION;
    }

    public static final String RFB_CLASS_DUMP_PREFIX = "RFB_CLASS_DUMP";

    /** Controlled by system property {@code rfb.dumpLoadedClasses=false}, whether post-transform classes should be dumped to RFB_CLASS_DUMP/ */
    public static final boolean cfgDumpLoadedClasses = getBooleanOr("rfb.dumpLoadedClasses", false)
            || Boolean.parseBoolean(System.getProperty("legacy.debugClassLoadingSave", "false"));

    /** Controlled by system property {@code rfb.dumpLoadedClassesPerTransformer=false}, whether loaded classes should be dumped to RFB_CLASS_DUMP/, with a file per asm transformer */
    public static final boolean cfgDumpLoadedClassesPerTransformer =
            getBooleanOr("rfb.dumpLoadedClassesPerTransformer", false)
                    || Boolean.parseBoolean(System.getProperty("legacy.debugClassLoadingSave", "false"));

    /** Controlled by system property {@code rfb.registerURLStreamHandler=true}, if the compatibility URLStreamHandler should be registered (there can only be one per JVM) */
    public static final boolean cfgRegisterURLStreamHandler = getBooleanOr("rfb.registerURLStreamHandler", true);
    /** Controlled by system property {@code rfb.dumpClassesAsynchronously=true}, if the class dumps are done from another Thread to avoid slow IO */
    public static final boolean cfgDumpClassesAsynchronously = getBooleanOr("rfb.dumpClassesAsynchronously", true);

    /** The target class dumping directory, initialized during commandline option parsing. */
    public static AtomicReference<Path> classDumpDirectory = new AtomicReference<>(null);

    /** Game version/profile name as parsed during early startup. */
    public static String initialGameVersion;
    /** Game directory as parsed during early startup. */
    public static File initialGameDir;
    /** Assets directory as parsed during early startup. */
    public static File initialAssetsDir;
    /** Public logger accessor for convenience */
    public static Logger logger = LogManager.getLogger("LaunchWrapper");

    public static final String JAVA_VERSION = URLClassLoaderBase.getJavaVersion();
    public static final int JAVA_MAJOR_VERSION = URLClassLoaderBase.getJavaMajorVersion();

    private static final ExecutorService classDumpingService = cfgDumpClassesAsynchronously
            ? Executors.newFixedThreadPool(
                    Math.min(4, Runtime.getRuntime().availableProcessors()), new ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            final Thread t = new Thread(r);
                            t.setName("RFB Class Dumping Executor");
                            t.setDaemon(true);
                            return t;
                        }
                    })
            : null;

    /** A utility to convert the java.class.path system property to an array of URLs */
    public static URL[] getUrlClasspathEntries() {
        return SimpleTransformingClassLoader.getUrlClasspathEntries(appClassLoader);
    }

    /**
     * @return An immutable view on compatibility transformers.
     */
    public static List<SimpleClassTransformer> getCompatibilityTransformers() {
        return Collections.unmodifiableList(Arrays.asList(compatibilityTransformers.get()));
    }

    /**
     * Updates the compatibility transformers list using the given function, mutator might be called multiple times if there's multiple threads racing to modify the list.
     * @param mutator A function that modifies a mutable List of compatibility transformers.
     */
    public static void mutateCompatibilityTransformers(Consumer<List<SimpleClassTransformer>> mutator) {
        while (true) {
            final SimpleClassTransformer[] original = compatibilityTransformers.get();
            final ArrayList<SimpleClassTransformer> mutable = new ArrayList<>(Arrays.asList(original));
            mutator.accept(mutable);
            final SimpleClassTransformer[] modified = mutable.toArray(new SimpleClassTransformer[0]);
            if (compatibilityTransformers.compareAndSet(original, modified)) {
                break;
            }
        }
    }

    public static void main(String[] args) throws Throwable {
        if (!(ClassLoader.getSystemClassLoader() instanceof SimpleTransformingClassLoader)) {
            throw new IllegalStateException(
                    "System classloader not overwritten, add -Djava.system.class.loader=com.gtnewhorizons.retrofuturabootstrap.SimpleTransformingClassLoader to your JVM flags");
        }
        Main.compatLoader = (SimpleTransformingClassLoader) ClassLoader.getSystemClassLoader();
        try {
            Class<?> launchClass = Class.forName("net.minecraft.launchwrapper.Launch", true, compatLoader);
            Method main = launchClass.getMethod("main", String[].class);
            main.invoke(null, (Object) args);
        } catch (InvocationTargetException ite) {
            throw ite.getCause(); // clean up stacktrace
        } finally {
            if (classDumpingService != null) {
                classDumpingService.shutdown();
                if (!classDumpingService.isTerminated()) {
                    logger.info("Waiting for final class dumps to finish...");
                    if (!classDumpingService.awaitTermination(10, TimeUnit.SECONDS)) {
                        logger.warn("Classes did not finish dumping in 10 seconds, aborting.");
                        classDumpingService.shutdownNow();
                    }
                }
            }
        }
    }

    /**
     * Asynchronously dumps the given class data if dumping is enabled.
     * @param className Regular class name (a.b.C)
     * @param classBytes The bytes to save, assumed to not be modified after passing into this method call
     */
    public static void dumpClass(String classLoaderName, String className, byte[] classBytes) {
        if (className == null || classBytes == null || className.isEmpty()) {
            return;
        }
        try {
            final Path dumpRoot = Main.classDumpDirectory.get();
            if (dumpRoot == null) {
                return;
            }
            final Path clRoot = (classLoaderName == null || classLoaderName.isEmpty())
                    ? dumpRoot
                    : dumpRoot.resolve(classLoaderName);
            final String internalName = className.replace('.', '/');
            final Path targetPath = clRoot.resolve(internalName + ".class");
            if (cfgDumpClassesAsynchronously) {
                classDumpingService.submit(() -> {
                    try {
                        Files.createDirectories(targetPath.getParent());
                        Files.write(
                                targetPath,
                                classBytes,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE,
                                StandardOpenOption.TRUNCATE_EXISTING);
                    } catch (IOException e) {
                        logger.warn("Could not save transformed class", e);
                    }
                });
            } else {
                Files.createDirectories(targetPath.getParent());
                Files.write(
                        targetPath,
                        classBytes,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (IOException | RejectedExecutionException e) {
            logger.warn("Could not save transformed class", e);
        }
    }
}
