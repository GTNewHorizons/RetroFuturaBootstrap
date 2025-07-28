package com.gtnewhorizons.retrofuturabootstrap;

import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformerHandle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Simple class that holds various runtime configuration for RFB, it cannot depend on most libraries due to being used early in the system class loader init process. */
public class SharedConfig {
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

    /** Controlled by system property {@code rfb.unsafeClassValidation=true}, if classpath contains obfuscated classes with broken CAFEBABE magic number to avoid crash */
    public static final boolean cfgUnsafeClassValidation = getBooleanOr("rfb.unsafeClassValidation", false);

    /** The target class dumping directory, initialized during commandline option parsing. */
    public static @NotNull AtomicReference<@Nullable Path> classDumpDirectory = new AtomicReference<>(null);

    /** An ArrayList of all RFB class transformers used, mutable, in order of application */
    static final @NotNull AtomicReference<@NotNull RfbClassTransformerHandle[]> rfbTransformers =
            new AtomicReference<>(new RfbClassTransformerHandle[0]);

    static @Nullable ExecutorService classDumpingService;
    // Replaced by log4j when initialized
    static @NotNull BiConsumer<String, Throwable> warnLogHandler = (msg, throwable) -> {
        if (throwable != null) {
            System.err.printf("[WARN] %s: %s\n", msg, throwable.getMessage());
            throwable.printStackTrace(System.err);
        } else {
            System.err.printf("[WARN] %s\n", msg);
        }
    };
    // Replaced by log4j when initialized
    static @NotNull BiConsumer<String, Throwable> debugLogHandler = (msg, throwable) -> {
        if (throwable != null) {
            System.out.printf("[DEBUG] %s: %s\n", msg, throwable.getMessage());
            throwable.printStackTrace(System.out);
        } else {
            System.out.printf("[DEBUG] %s\n", msg);
        }
    };

    /**
     * SystemClassLoader-safe warning log wrapper.
     */
    public static void logWarning(String message, @Nullable Throwable throwable) {
        warnLogHandler.accept(message, throwable);
    }

    /**
     * SystemClassLoader-safe debug log wrapper.
     */
    public static void logDebug(String message, @Nullable Throwable throwable) {
        debugLogHandler.accept(message, throwable);
    }

    /**
     * @return An immutable view on RFB transformers.
     */
    public static @NotNull List<@NotNull RfbClassTransformerHandle> getRfbTransformers() {
        return Collections.unmodifiableList(Arrays.asList(rfbTransformers.get()));
    }

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

    /**
     * Asynchronously dumps the given class data if dumping is enabled and initialized, synchronously before Main is called.
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
            // Replace $->. because otherwise the files are invisible in IntelliJ
            final String internalName = className.replace('.', '/').replace('$', '.');
            final Path targetPath = clRoot.resolve(internalName + ".class");
            final ExecutorService service = classDumpingService;
            if (cfgDumpClassesAsynchronously && service != null) {
                service.submit(() -> {
                    try {
                        Files.createDirectories(targetPath.getParent());
                        Files.write(
                                targetPath,
                                classBytes,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE,
                                StandardOpenOption.TRUNCATE_EXISTING);
                    } catch (IOException e) {
                        logWarning("Could not save transformed class", e);
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
            logWarning("Could not save transformed class", e);
        }
    }
}
