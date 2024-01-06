package com.gtnewhorizons.retrofuturabootstrap;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.launchwrapper.LogWrapper;

public class Main {
    /** The compatibility ClassLoader that's a parent of LaunchClassLoader. */
    public static SimpleTransformingClassLoader compatLoader;
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
        if (appClassLoader instanceof URLClassLoader) {
            return ((URLClassLoader) appClassLoader).getURLs();
        }
        return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(path -> {
                    try {
                        return new File(path).toURI().toURL();
                    } catch (MalformedURLException e) {
                        LogWrapper.warning("Could not parse {} into an URL", path, e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toArray(URL[]::new);
    }

    public static void main(String[] args) throws Throwable {
        if (cfgRegisterURLStreamHandler) {
            URLProtocolFactory.register();
        }
        final URL[] cpEntries = getUrlClasspathEntries();
        final SimpleTransformingClassLoader compatLoader = new SimpleTransformingClassLoader("RFB-Compat", cpEntries);
        Thread.currentThread().setContextClassLoader(compatLoader);
        Main.compatLoader = compatLoader;
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
                    LogWrapper.logger.info("Waiting for final class dumps to finish...");
                    if (!classDumpingService.awaitTermination(10, TimeUnit.SECONDS)) {
                        LogWrapper.logger.warn("Classes did not finish dumping in 10 seconds, aborting.");
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
                        LogWrapper.logger.warn("Could not save transformed class", e);
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
            LogWrapper.logger.warn("Could not save transformed class", e);
        }
    }
}
