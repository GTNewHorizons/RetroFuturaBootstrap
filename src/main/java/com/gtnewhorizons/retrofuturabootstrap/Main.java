package com.gtnewhorizons.retrofuturabootstrap;

import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformerHandle;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Main {
    /** The RFB ClassLoader that's a parent of LaunchClassLoader. */
    public @Nullable static RfbSystemClassLoader compatLoader;
    /** The LaunchClassLoader handle, can't use the type directly because it's loaded in a different classloader. */
    public @Nullable static ExtensibleClassLoader launchLoader;
    /** An ArrayList of all RFB class transformers used, mutable, in order of application */
    private static final @NotNull AtomicReference<@NotNull RfbClassTransformerHandle[]> rfbTransformers =
            new AtomicReference<>(new RfbClassTransformerHandle[0]);
    /** The ClassLoader that loaded this class. */
    public static final @NotNull ClassLoader appClassLoader = Main.class.getClassLoader();

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
    public static @NotNull String getVersion() {
        return BuildConfig.VERSION;
    }

    public static final @NotNull String RFB_CLASS_DUMP_PREFIX = "RFB_CLASS_DUMP";

    /** Controlled by system property {@code rfb.dumpLoadedClasses=false}, whether post-transform classes should be dumped to RFB_CLASS_DUMP/ */
    public static final boolean cfgDumpLoadedClasses = getBooleanOr("rfb.dumpLoadedClasses", false)
            || Boolean.parseBoolean(System.getProperty("legacy.debugClassLoadingSave", "false"));

    /** Controlled by system property {@code rfb.dumpLoadedClassesPerTransformer=false}, whether loaded classes should be dumped to RFB_CLASS_DUMP/, with a file per asm transformer */
    public static final boolean cfgDumpLoadedClassesPerTransformer =
            getBooleanOr("rfb.dumpLoadedClassesPerTransformer", false)
                    || Boolean.parseBoolean(System.getProperty("legacy.debugClassLoadingSave", "false"));

    /** Controlled by system property {@code rfb.dumpClassesAsynchronously=true}, if the class dumps are done from another Thread to avoid slow IO */
    public static final boolean cfgDumpClassesAsynchronously = getBooleanOr("rfb.dumpClassesAsynchronously", true);

    /** The target class dumping directory, initialized during commandline option parsing. */
    public static @NotNull AtomicReference<@Nullable Path> classDumpDirectory = new AtomicReference<>(null);

    /** Game version/profile name as parsed during early startup. */
    public static @Nullable String initialGameVersion;
    /** Game directory as parsed during early startup. */
    public static @Nullable File initialGameDir;
    /** Assets directory as parsed during early startup. */
    public static @Nullable File initialAssetsDir;
    /** Public logger accessor for convenience */
    public static @NotNull Logger logger = LogManager.getLogger("LaunchWrapper");

    public static final @NotNull String JAVA_VERSION = URLClassLoaderBase.getJavaVersion();
    public static final int JAVA_MAJOR_VERSION = URLClassLoaderBase.getJavaMajorVersion();

    private static final @Nullable ExecutorService classDumpingService = cfgDumpClassesAsynchronously
            ? Executors.newFixedThreadPool(Math.min(4, Runtime.getRuntime().availableProcessors()), runnable -> {
                final Thread t = new Thread(runnable);
                t.setName("RFB Class Dumping Executor");
                t.setDaemon(true);
                return t;
            })
            : null;

    /** A utility to convert the java.class.path system property to an array of URLs */
    public static @NotNull URL @NotNull [] getUrlClasspathEntries() {
        return RfbSystemClassLoader.getUrlClasspathEntries(appClassLoader);
    }

    /** Adds a given URL to the classpath of both the compat and launch loaders, if not already added */
    public static void addClasspathUrl(@NotNull URL url) {
        if (compatLoader != null) {
            compatLoader.addURL(url);
        }
        if (launchLoader != null) {
            launchLoader.addURL(url);
        }
    }

    /**
     * @return An immutable view on RFB transformers.
     */
    public static @NotNull List<@NotNull RfbClassTransformerHandle> getRfbTransformers() {
        return Collections.unmodifiableList(Arrays.asList(rfbTransformers.get()));
    }

    /**
     * Updates the RFB transformers list using the given function, mutator might be called multiple times if there's multiple threads racing to modify the list.
     * @param mutator A function that modifies a mutable List of RFB transformers.
     */
    public static void mutateRfbTransformers(
            @NotNull Consumer<@NotNull List<@NotNull RfbClassTransformerHandle>> mutator) {
        while (true) {
            final RfbClassTransformerHandle[] original = rfbTransformers.get();
            final ArrayList<RfbClassTransformerHandle> mutable = new ArrayList<>(Arrays.asList(original));
            mutator.accept(mutable);
            final RfbClassTransformerHandle[] modified = mutable.toArray(new RfbClassTransformerHandle[0]);
            if (rfbTransformers.compareAndSet(original, modified)) {
                break;
            }
        }
    }

    public static void main(String[] args) throws Throwable {
        if (!(ClassLoader.getSystemClassLoader() instanceof RfbSystemClassLoader)) {
            throw new IllegalStateException(
                    "System classloader not overwritten, add -Djava.system.class.loader=com.gtnewhorizons.retrofuturabootstrap.RfbSystemClassLoader to your JVM flags");
        }
        Main.compatLoader = (RfbSystemClassLoader) ClassLoader.getSystemClassLoader();
        if (JAVA_MAJOR_VERSION > 8 && URLClassLoaderBase.implementationVersion() == 8) {
            throw new IllegalStateException(
                    "Java newer than 8 is used, while the URLClassLoaderBase for Java 8 was loaded by "
                            + URLClassLoaderBase.class
                                    .getClassLoader()
                                    .getClass()
                                    .getName());
        }
        if (classDumpingService != null) {
            Runtime.getRuntime()
                    .addShutdownHook(new Thread(
                            () -> {
                                classDumpingService.shutdown();
                                if (!classDumpingService.isTerminated()) {
                                    logger.info("Waiting for final class dumps to finish...");
                                    try {
                                        if (!classDumpingService.awaitTermination(10, TimeUnit.SECONDS)) {
                                            logger.warn("Classes did not finish dumping in 10 seconds, aborting.");
                                            classDumpingService.shutdownNow();
                                        }
                                    } catch (InterruptedException e) {
                                        logger.warn("Classes did not finish dumping in 10 seconds, aborting.");
                                        classDumpingService.shutdownNow();
                                    }
                                }
                            },
                            "RFB ClassDumpingService Shutdown hook"));
        }
        try {
            Class<?> launchClass = Class.forName("net.minecraft.launchwrapper.Launch", true, compatLoader);
            Method main = launchClass.getMethod("main", String[].class);
            main.invoke(null, (Object) args);
        } catch (InvocationTargetException ite) {
            throw ite.getCause(); // clean up stacktrace
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
            if (cfgDumpClassesAsynchronously && classDumpingService != null) {
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
