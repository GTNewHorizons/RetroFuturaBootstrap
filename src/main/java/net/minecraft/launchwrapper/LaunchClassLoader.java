package net.minecraft.launchwrapper;

import com.gtnewhorizons.retrofuturabootstrap.Main;
import com.gtnewhorizons.retrofuturabootstrap.RfbSystemClassLoader;
import com.gtnewhorizons.retrofuturabootstrap.URLClassLoaderWithUtilities;
import com.gtnewhorizons.retrofuturabootstrap.api.ClassHeaderMetadata;
import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import com.gtnewhorizons.retrofuturabootstrap.api.FastClassAccessor;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import com.gtnewhorizons.retrofuturabootstrap.asm.SafeAsmClassWriter;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Manifest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassWriter;

public class LaunchClassLoader extends URLClassLoaderWithUtilities implements ExtensibleClassLoader {

    /** Internal IO buffer size */
    public static final int BUFFER_SIZE = 1 << 12;
    /** A list keeping track of addURL calls */
    private List<URL> sources;
    /** A reference to the classloader that loaded this class */
    private ClassLoader parent = getClass().getClassLoader();
    /** RFB: null or RfbSystemClassLoader reference to parent */
    private final RfbSystemClassLoader rfb$parent =
            (parent instanceof RfbSystemClassLoader) ? ((RfbSystemClassLoader) parent) : null;
    /** RFB: Reference to the platform class loader that can load JRE/JDK classes */
    private static final ClassLoader rfb$platformLoader = getPlatformClassLoader();

    /** An ArrayList of all class transformers used, mutable, often modified via reflection */
    private List<IClassTransformer> transformers = new ArrayList<>(2);
    /** A ConcurrentHashMap cache of all classes loaded via this loader */
    private Map<String, Class<?>> cachedClasses = new ConcurrentHashMap<>();
    /**
     * A HashSet (probably wrong, not thread safe) cache of class names that previously caused exceptions when
     * attempting to load them
     */
    private Set<String> invalidClasses = Collections.newSetFromMap(new ConcurrentHashMap<>(1000));

    /**
     * A HashSet of all class prefixes (e.g. "org.lwjgl.") to redirect to the parent classloader, often modified via
     * reflection
     */
    private Set<String> classLoaderExceptions = new HashSet<>();
    /**
     * A HashSet of all class prefixes (e.g. "org.objectweb.asm.") to NOT run class transformers on, often modified via
     * reflection
     */
    private Set<String> transformerExceptions = new HashSet<>();
    /**
     * An unused cache of package manifests, the field with a non-null CHM value needs to stay here due to reflective
     * usage
     */
    private Map<Package, Manifest> packageManifests = new ConcurrentHashMap<>();
    /** A ConcurrentHashMap cache of class bytes loaded via this classloader */
    private Map<String, byte[]> resourceCache = new ConcurrentHashMap<>(1000);
    /** A concurrent cache of class bytes that previously caused exceptions when attempting to load them */
    private Set<String> negativeResourceCache = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** A transformer used to remap class names */
    private IClassNameTransformer renameTransformer;

    /** A dummy empty manifest field, used reflectively by some mods */
    private static final Manifest EMPTY = new Manifest();

    /** A utility IO buffer */
    private final ThreadLocal<byte[]> loadBuffer = new ThreadLocal<>();

    /**
     * A list of filenames forbidden by Windows, <a
     * href="https://learn.microsoft.com/en-us/windows/win32/fileio/naming-a-file#naming-conventions">MSDN entry</a>
     */
    private static final String[] RESERVED_NAMES = {
        "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1",
        "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    };

    /** A system property determining if additional debug output should be generated when loading classes */
    private static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("legacy.debugClassLoading", "false"));
    /**
     * A system property determining if additional debug output should be generated when loading classes on top of
     * DEBUG
     */
    private static final boolean DEBUG_FINER =
            DEBUG && Boolean.parseBoolean(System.getProperty("legacy.debugClassLoadingFiner", "false"));
    /** A system property determining if post-transform class bytes should be dumped on top of DEBUG */
    private static final boolean DEBUG_SAVE =
            DEBUG && Boolean.parseBoolean(System.getProperty("legacy.debugClassLoadingSave", "false"));
    /** Folder where the class dumps are saved */
    private static File tempFolder = null;

    /**
     * <ol>
     *     <li>Constructs a parent-less super URLClassLoader</li>
     *     <li>Saves the given classpath to this.sources</li>
     *     <li>Adds class loader exclusions for:<ul>
     *         <li>java.</li>
     *         <li>sun.</li>
     *         <li>org.lwjgl.</li>
     *         <li>org.apache.logging.</li>
     *         <li>net.minecraft.launchwrapper.</li>
     *     </ul></li>
     *     <li>Adds transformer exclusions for:<ul>
     *         <li>javax.</li>
     *         <li>argo.</li>
     *         <li>org.objectweb.asm.</li>
     *         <li>com.google.common.</li>
     *         <li>org.bouncycastle.</li>
     *         <li>net.minecraft.launchwrapper.injector.</li>
     *     </ul></li>
     *     <li>If DEBUG_SAVE, finds a suitable directory to dump classes to, mkdirs it and saves it to tempFolder</li>
     * </ol>
     *
     * @param sources The initial classpath
     */
    public LaunchClassLoader(URL[] sources) {
        super("RFB-Launch", sources, getPlatformClassLoader());
        LogWrapper.configureLogging();
        this.sources = new ArrayList<>(Arrays.asList(sources));
        classLoaderExceptions.addAll(Arrays.asList(
                "java.",
                "sun.",
                "org.lwjgl.",
                "org.apache.logging.",
                "org.objectweb.asm.",
                "net.minecraft.launchwrapper.",
                "org.slf4j.",
                "com.gtnewhorizons.retrofuturabootstrap."));
        transformerExceptions.addAll(Arrays.asList(
                "javax.",
                "argo.",
                "com.google.common.",
                "org.bouncycastle.",
                "net.minecraft.launchwrapper.injector.",
                "com.gtnewhorizons.retrofuturabootstrap."));
    }

    /**
     * Reflectively constructs the given transformer, and adds it to the transformer list. If the transformer is a
     * IClassNameTransformer and one wasn't already registered, sets renameTransformer to it. Catches any exceptions,
     * and ignores them while logging a message.
     *
     * @param transformerClassName A class name (like a.b.Cde) of the transformer.
     */
    public void registerTransformer(String transformerClassName) {
        try {
            Class<?> xformerClass = Class.forName(transformerClassName, true, this);
            if (!IClassTransformer.class.isAssignableFrom(xformerClass)) {
                LogWrapper.severe(
                        "Tried to register a transformer {} which does not implement IClassTransformer",
                        transformerClassName);
                return;
            }
            IClassTransformer xformer =
                    (IClassTransformer) xformerClass.getConstructor().newInstance();
            if (renameTransformer == null && xformer instanceof IClassNameTransformer) {
                renameTransformer = (IClassNameTransformer) xformer;
            }
            transformers.add(xformer);
            LogWrapper.rfb$logger.debug("Registered class transformer {}", transformerClassName);
        } catch (Throwable e) {
            Throwable cause = e;
            if (cause instanceof InvocationTargetException) {
                cause = cause.getCause();
            }
            LogWrapper.rfb$logger.warn("Could not register a transformer {}", transformerClassName, cause);
        }
    }

    @Override
    public Class<?> findCachedClass(final String name) {
        return cachedClasses.get(name);
    }

    /**
     * <ol>
     *     <li>If invalidClasses contains name, throw ClassNotFoundException</li>
     *     <li>If name starts with any class loader exception, forward to parent.loadClass</li>
     *     <li>If in cachedClasses, return the cached class</li>
     *     <li>If name starts with any transformer exception, super.findClass, put into cachedClasses and return. Catch and cache ClassNotFoundException.</li>
     *     <li>transformName, and check the cache again - if present, return from cache</li>
     *     <li>untransformName, find the last dot and use that to determine the package name and file path of the .class file</li>
     *     <li>Open a URLConnection using findCodeSourceConnectionFor on the determined filename</li>
     *     <li>Check package sealing for the given URLConnection, unless the untransformed name starts with "net.minecraft.", giving a severe warning if the package is already sealed. Otherwise, create and register a new Package.</li>
     *     <li>runTransformers on getClassBytes</li>
     *     <li>Save the debug class if enabled</li>
     *     <li>defineClass with the appropriate CodeSigners</li>
     *     <li>Cache and return the class</li>
     *     <li>Throw a ClassNotFoundException if any exception happens during the transforming process</li>
     * </ol>
     */
    @Override
    public @NotNull Class<?> findClass(final @NotNull String name) throws ClassNotFoundException {
        if (invalidClasses.contains(name)) {
            throw new ClassNotFoundException(name + " in invalid class cache");
        }
        for (final String exception : classLoaderExceptions) {
            if (name.startsWith(exception)) {
                return parent.loadClass(name);
            }
        }
        {
            final Class<?> cached = cachedClasses.get(name);
            if (cached != null) {
                return cached;
            }
        }
        boolean runTransformers = true;
        for (final String exception : transformerExceptions) {
            if (name.startsWith(exception)) {
                runTransformers = false;
                break;
            }
        }
        final String transformedName = runTransformers ? transformName(name) : name;
        {
            Class<?> transformedClass = cachedClasses.get(transformedName);
            if (transformedClass != null) {
                return transformedClass;
            }
        }
        final String untransformedName = runTransformers ? untransformName(name) : name;
        final int lastDot = untransformedName.lastIndexOf('.');
        final String packageName = (lastDot == -1) ? "" : untransformedName.substring(0, lastDot);
        final String classPath = untransformedName.replace('.', '/') + ".class";
        final URLConnection connection = findCodeSourceConnectionFor(classPath);
        final Package pkg;
        final CodeSource codeSource;
        Manifest manifest = null;
        byte[] classBytes = null;
        if (!packageName.isEmpty()) {
            if (!untransformedName.startsWith("net.minecraft.") && connection instanceof JarURLConnection) {
                final JarURLConnection jarConnection = (JarURLConnection) connection;
                final URL packageSourceUrl = jarConnection.getJarFileURL();
                CodeSigner[] codeSigners = null;
                try {
                    manifest = jarConnection.getManifest();
                    pkg = getAndVerifyPackage(packageName, manifest, packageSourceUrl);
                    classBytes = runTransformers
                            ? getClassBytes(untransformedName)
                            : rfb$getUncachedClassBytes(untransformedName);
                    codeSigners = jarConnection.getJarEntry().getCodeSigners();
                } catch (IOException e) {
                    // no-op
                }
                // LaunchClassLoader was buggy here and used the nested jar!file URL instead of just the jar URL,
                // unlike regular Java ClassLoaders. It used the jar URL when transformer exclusions applied though.
                final URL classSourceUrl = runTransformers ? jarConnection.getURL() : jarConnection.getJarFileURL();
                codeSource = new CodeSource(classSourceUrl, codeSigners);
            } else {
                pkg = getAndVerifyPackage(packageName, null, null);
                codeSource = connection == null ? null : new CodeSource(connection.getURL(), (CodeSigner[]) null);
            }
        } else {
            pkg = null;
            final URL url = connection == null ? null : connection.getURL();
            codeSource = url == null ? null : new CodeSource(url, (CodeSigner[]) null);
        }
        if (classBytes == null) {
            try {
                classBytes = runTransformers
                        ? getClassBytes(untransformedName)
                        : rfb$getUncachedClassBytes(untransformedName);
            } catch (IOException e) {
                /* no-op */
            }
        }
        if (Main.cfgDumpLoadedClassesPerTransformer && classBytes != null) {
            Main.dumpClass(this.getClassLoaderName(), transformedName + "__000_pretransform", classBytes);
        }
        if (runTransformers) {
            try {
                classBytes = runTransformers(untransformedName, transformedName, classBytes);
            } catch (Throwable t) {
                ClassNotFoundException err =
                        new ClassNotFoundException("Exception caught while transforming class " + name, t);
                LogWrapper.rfb$logger.debug("Transformer error", err);
                throw err;
            }
        }
        if (rfb$parent != null) {
            boolean doCompatTransforms = true;
            for (String exclusion : rfb$parent.childDelegations) {
                if (transformedName.startsWith(exclusion)) {
                    doCompatTransforms = false;
                    break;
                }
            }
            if (doCompatTransforms) {
                try {
                    final RfbClassTransformer.Context context = runTransformers
                            ? RfbClassTransformer.Context.LCL_WITH_TRANSFORMS
                            : RfbClassTransformer.Context.LCL_NO_TRANSFORMS;
                    classBytes = runRfbTransformers(
                            Main.getRfbTransformers(), context, manifest, transformedName, classBytes);
                } catch (Throwable t) {
                    ClassNotFoundException err =
                            new ClassNotFoundException("Exception caught while transforming class " + name, t);
                    LogWrapper.rfb$logger.debug("Transformer error", err);
                    throw err;
                }
            }
        }
        if (classBytes == null) {
            invalidClasses.add(name);
            throw new ClassNotFoundException(String.format("Class bytes are null for %s (%s, %s)", name, name, name));
        }
        if (Main.cfgDumpLoadedClasses) {
            Main.dumpClass(this.getClassLoaderName(), transformedName, classBytes);
        }
        Class<?> result = defineClass(transformedName, classBytes, 0, classBytes.length, codeSource);
        cachedClasses.put(transformedName, result);
        return result;
    }

    // based off OpenJDK's own URLClassLoader
    public Package getAndVerifyPackage(final String packageName, final Manifest manifest, final URL codeSourceURL) {
        return super.getAndVerifyPackage(packageName, manifest, codeSourceURL);
    }

    /**
     * <ol>
     *     <li>If tempFolder is null, return immediately</li>
     *     <li>Generate an output path for a class org.my.Klass as tempFolder/org/my/Klass.class</li>
     *     <li>Make the directory if it doesn't already exist, delete the output file if it already exists</li>
     *     <li>Write data to the file, ignoring exceptions</li>
     * </ol>
     */
    private void saveTransformedClass(final byte[] data, final String transformedName) {
        Main.dumpClass(this.getClassLoaderName(), transformedName, data);
    }

    /** A null-safe version of renameTransformer.unmapClassName(name) */
    private String untransformName(final String name) {
        if (renameTransformer == null || name == null) {
            return name;
        }
        final String newName = renameTransformer.unmapClassName(name);
        return newName == null ? name : newName;
    }

    /** A null-safe version of renameTransformer.remapClassName(name) */
    private String transformName(final String name) {
        if (renameTransformer == null || name == null) {
            return name;
        }
        final String newName = renameTransformer.remapClassName(name);
        return newName == null ? name : newName;
    }

    /**
     * {@inheritDoc}
     */
    public boolean isSealed(final String packageName, final Manifest manifest) {
        return super.isSealed(packageName, manifest);
    }

    /**
     * Calls findResource, and if it's not null opens a connection to it, otherwise returns null.
     */
    private URLConnection findCodeSourceConnectionFor(final String name) {
        try {
            final URL url = findResource(name);
            if (url == null) {
                return null;
            }
            return url.openConnection();
        } catch (Exception e) {
            LogWrapper.rfb$logger.debug("Couldn't findCodeSourceConnectionFor {}: {}", name, e.getMessage());
            return null;
        }
    }

    /**
     * <ol>
     *     <li>For each transformer on the transformer list, transform basicClass</li>
     *     <li>Return the updated basicClass</li>
     * </ol>
     */
    private byte[] runTransformers(final String name, final String transformedName, byte[] basicClass) {
        int xformerIndex = 1;
        for (IClassTransformer xformer : transformers) {
            try {
                byte[] newKlass;
                try {
                    newKlass = xformer.transform(name, transformedName, basicClass);
                } catch (Exception e) {
                    // retry in case of invalid frames written
                    if (e.getStackTrace() != null
                            && e.getStackTrace().length > 2
                            && e.getStackTrace()[0].getClassName().contains("asm.MethodWriter")) {
                        SafeAsmClassWriter.forcedFlags.set(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                        SafeAsmClassWriter.forcedOriginalClass.set(basicClass);
                        newKlass = xformer.transform(name, transformedName, basicClass);
                        SafeAsmClassWriter.forcedOriginalClass.set(null);
                        SafeAsmClassWriter.forcedFlags.set(0);
                        LogWrapper.rfb$logger.warn(
                                "Transformer {} did not generate correct frames for {}, had to re-compute using asm.",
                                xformer.getClass().getName(),
                                transformedName);
                    } else {
                        throw e;
                    }
                }
                if (Main.cfgDumpLoadedClassesPerTransformer
                        && newKlass != null
                        && !Arrays.equals(basicClass, newKlass)) {
                    Main.dumpClass(
                            this.getClassLoaderName(),
                            String.format(
                                    "%s__%03d_%s",
                                    transformedName,
                                    xformerIndex,
                                    xformer.getClass()
                                            .getName()
                                            .replace('/', '_')
                                            .replace('.', '_')),
                            newKlass);
                }
                basicClass = newKlass;
            } catch (UnsupportedOperationException e) {
                if (e.getMessage().contains("requires ASM")) {
                    LogWrapper.rfb$logger.warn(
                            "ASM transformer {} encountered a newer classfile ({} -> {}) than supported: {}",
                            xformer.getClass().getName(),
                            name,
                            transformedName,
                            e.getMessage());
                    continue;
                }
                throw e;
            }
            xformerIndex++;
        }
        return basicClass;
    }

    /**
     * Adds the given url to the classpath (via super) and the sources field.
     */
    @Override
    public void addURL(final URL url) {
        super.addURL(url);
        sources.add(url);
    }

    @Override
    public void addSilentURL(@Nullable URL url) {
        super.addURL(url);
    }

    /** Returns the saved classpath list */
    public List<URL> getSources() {
        return sources;
    }

    /**
     * Tries to fully read the input stream into a byte array<strike>, using getOrCreateBuffer() as the IO
     * buffer</strike>. Returns an empty array and logs a warning if any exception happens.
     */
    private byte[] readFully(InputStream stream) {
        try {
            return readAllBytes(stream, getOrCreateBuffer());
        } catch (Exception e) {
            LogWrapper.rfb$logger.warn("Could not read InputStream {}", stream.toString(), e);
            return new byte[0];
        }
    }

    /**
     * A TLS helper for the loadBuffer field, creates a byte[BUFFER_SIZE] array if empty.
     */
    @SuppressWarnings("unused") // keep for reflection compat if needed
    private byte[] getOrCreateBuffer() {
        byte[] buf = loadBuffer.get();
        if (buf == null) {
            buf = new byte[BUFFER_SIZE];
            loadBuffer.set(buf);
        }
        return buf;
    }

    /** Returns an unmodifiable view of the transformers list */
    public List<IClassTransformer> getTransformers() {
        return Collections.unmodifiableList(transformers);
    }

    /** RFB: Returns a modifiable view of the transformers list */
    public List<IClassTransformer> rfb$getMutableTransformers() {
        return transformers;
    }

    /** Adds a new entry to the end of the class loader exclusions list */
    public void addClassLoaderExclusion(String toExclude) {
        classLoaderExceptions.add(toExclude);
    }

    /** Adds a new entry to the end of the transformer exclusions list */
    public void addTransformerExclusion(String toExclude) {
        transformerExceptions.add(toExclude);
    }

    @Override
    public @Nullable FastClassAccessor findClassMetadata(@NotNull String name) {
        FastClassAccessor acc = findClassMetadataImpl(name);
        if (acc == null && renameTransformer != null) {
            name = untransformName(name);
            acc = findClassMetadataImpl(name);
        }
        return acc;
    }

    public @Nullable FastClassAccessor findClassMetadataImpl(@NotNull String name) {
        for (final String exception : classLoaderExceptions) {
            if (name.startsWith(exception)) {
                try {
                    final Class<?> loaded = parent.loadClass(name);
                    return FastClassAccessor.ofLoaded(loaded);
                } catch (ClassNotFoundException e) {
                    // no-op
                }
                return null;
            }
        }
        final Class<?> cachedClass = findCachedClass(name);
        if (cachedClass != null) {
            return FastClassAccessor.ofLoaded(cachedClass);
        }
        try {
            final byte[] classBytes = getClassBytes(name);
            if (classBytes != null) {
                return new ClassHeaderMetadata(classBytes);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    /**
     * <ol>
     *     <li>If negativeResourceCache contains name, return null</li>
     *     <li>If resourceCache contains name, return the value</li>
     *     <li>If there is no '.' in the name and the name is one of the reserved names (ignore case), cache and return getClassBytes("_name")</li>
     *     <li>Find and load the resource with the path of name with . replaced with / and .class appended</li>
     *     <li>Cache and return the contents</li>
     *     <li>Silently close the stream regardless of any exceptions (but don't suppress exceptions)</li>
     * </ol>
     */
    public byte[] getClassBytes(String name) throws IOException {
        if (negativeResourceCache.contains(name)) {
            return null;
        }
        final byte[] cached = resourceCache.get(name);
        if (cached != null) {
            return cached.clone();
        }
        if (!name.contains(".") && name.length() >= 3 && name.length() <= 4) {
            for (final String reserved : RESERVED_NAMES) {
                if (reserved.equalsIgnoreCase(name)) {
                    final byte[] underscored = getClassBytes("_" + name);
                    if (underscored != null) {
                        resourceCache.put(name, underscored);
                    } else {
                        negativeResourceCache.add(name);
                    }
                    return underscored;
                }
            }
        }
        final byte[] data = rfb$getUncachedClassBytes(name);
        if (data == null) {
            negativeResourceCache.add(name);
            return null;
        }
        resourceCache.put(name, data);
        return data.clone();
    }

    private byte[] rfb$getUncachedClassBytes(String name) throws IOException {
        final String classPath = name.replace('.', '/') + ".class";
        final URL resourceUrl = findResource(classPath);
        URLConnection conn = resourceUrl == null ? null : resourceUrl.openConnection();
        if (conn == null) {
            if (rfb$platformLoader != null) {
                // Try JRE classes
                final URL platformUrl = rfb$platformLoader.getResource(classPath);
                if (platformUrl != null) {
                    conn = platformUrl.openConnection();
                }
            } else {
                // The only way to access com.sun BootClassLoader's resources
                final URL parentUrl = getResource(classPath);
                if (parentUrl != null) {
                    conn = parentUrl.openConnection();
                }
            }
        }
        if (conn == null) {
            return null;
        }
        final InputStream is = conn.getInputStream();
        final byte[] contents = readFully(is);
        closeSilently(is);
        return contents;
    }

    /** Null-safe, exception-safe close function that silently ignores any errors */
    private static void closeSilently(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable e) {
            // no-op
        }
    }

    /** Removes all of entriesToClear from negativeResourceCache */
    public void clearNegativeEntries(Set<String> entriesToClear) {
        negativeResourceCache.removeAll(entriesToClear);
    }

    @Override
    public @NotNull URLClassLoader asURLClassLoader() {
        return this;
    }
}
