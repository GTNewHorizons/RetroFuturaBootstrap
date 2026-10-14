package net.minecraft.launchwrapper;

import com.gtnewhorizons.retrofuturabootstrap.ClassPathIndex;
import com.gtnewhorizons.retrofuturabootstrap.ClassPreloader;
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
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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

    /** A CopyOnWriteArrayList of all class transformers used, mutable, often modified via reflection */
    private List<IClassTransformer> transformers = new CopyOnWriteArrayList<>();
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
    private Set<String> classLoaderExceptions = ConcurrentHashMap.newKeySet();
    /**
     * A HashSet of all class prefixes (e.g. "org.objectweb.asm.") to NOT run class transformers on, often modified via
     * reflection
     */
    private Set<String> transformerExceptions = ConcurrentHashMap.newKeySet();
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
        super("Launch", sources, getPlatformClassLoader());
        LogWrapper.configureLogging();
        this.sources = new ArrayList<>(Arrays.asList(sources));
        classLoaderExceptions.addAll(Arrays.asList(
                "java.",
                "javax.imageio.",
                "javax.sound.",
                "javax.swing.",
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
        final ClassPreloader<rfb$PreparedClass> preloader = rfb$preloader;
        if (!Thread.holdsLock(this)) {
            synchronized (this) {
                return findClass(name);
            }
        }
        if (preloader.isWorkerThread()) {
            return rfb$findClassOnWorker(preloader, name, runTransformers);
        }
        return rfb$findClassConcurrent(preloader, name, runTransformers);
    }

    // ---- RFB: class preloading ----
    //
    // Locks: this loader's monitor M and the transformer lock T held by whichever thread runs transformers.
    // The preload worker takes T without M, and M only for class loads its transformers trigger. Loading threads hold
    // M and take T only for classes that weren't prepared; they wait for T (and for other threads' in-progress loads
    // of the same class) with M.wait(), releasing M, so the worker never waits for M while they wait for T.

    private static final Object RFB_CLAIMED = new Object();
    private static final Object RFB_REENTRANT = new Object();
    private final Map<String, Thread> rfb$inFlight = new java.util.HashMap<>();
    private volatile int rfb$waiters;

    private final java.util.concurrent.atomic.AtomicReference<Thread> rfb$transformOwner =
            new java.util.concurrent.atomic.AtomicReference<>();
    /** Only accessed by the thread owning the transformer lock. */
    private int rfb$transformDepth;

    /** A loading thread (holding this loader's lock), a class that isn't loaded yet. */
    private Class<?> rfb$findClassConcurrent(
            final ClassPreloader<rfb$PreparedClass> preloader, final String name, final boolean runTransformers)
            throws ClassNotFoundException {
        final Object claim = rfb$claim(name);
        if (claim instanceof Class) {
            return (Class<?>) claim;
        }
        try {
            rfb$PreparedClass prepared = preloader.take(name);
            if (prepared != null && prepared.runTransformers != runTransformers) {
                prepared = null; // a transformer exclusion was added for this class since it was prepared
            }
            if (prepared != null) {
                final Class<?> defined = cachedClasses.get(prepared.transformedName);
                return defined != null ? defined : rfb$define(prepared);
            }
            rfb$lockTransformsWaiting();
            try {
                final String transformedName = runTransformers ? transformName(name) : name;
                final Class<?> transformedClass = cachedClasses.get(transformedName);
                if (transformedClass != null) {
                    return transformedClass;
                }
                return rfb$define(Objects.requireNonNull(rfb$prepare(name, runTransformers, transformedName, false)));
            } finally {
                rfb$unlockTransforms(preloader);
            }
        } finally {
            if (claim == RFB_CLAIMED) {
                rfb$inFlight.remove(name);
                if (rfb$waiters > 0) {
                    notifyAll();
                }
            }
        }
    }

    /** Waits (releasing this loader's lock) while another thread loads the same class. */
    private Object rfb$claim(final String name) {
        final Thread me = Thread.currentThread();
        boolean interrupted = false;
        try {
            while (true) {
                final Thread owner = rfb$inFlight.get(name);
                if (owner == null) {
                    rfb$inFlight.put(name, me);
                    return RFB_CLAIMED;
                }
                if (owner == me) {
                    return RFB_REENTRANT;
                }
                if (rfb$transformOwner.get() == me) {
                    return RFB_REENTRANT;
                }
                interrupted |= rfb$waitOnLoader();
                final Class<?> defined = cachedClasses.get(name);
                if (defined != null) {
                    return defined;
                }
            }
        } finally {
            if (interrupted) {
                me.interrupt();
            }
        }
    }

    /** Takes the transformer lock, waiting (releasing this loader's lock) while another thread holds it. */
    private void rfb$lockTransformsWaiting() {
        final Thread me = Thread.currentThread();
        if (rfb$transformOwner.get() == me) {
            rfb$transformDepth++;
            return;
        }
        if (!rfb$transformOwner.compareAndSet(null, me)) {
            boolean interrupted = false;
            rfb$waiters++; // before the next attempt, so a release in between sees it and notifies
            try {
                while (!rfb$transformOwner.compareAndSet(null, me)) {
                    interrupted |= rfb$waitOnLoader();
                }
            } finally {
                rfb$waiters--;
            }
            if (interrupted) {
                me.interrupt();
            }
        }
        rfb$transformDepth = 1;
    }

    /** For the preload worker, which holds no lock: takes the transformer lock if it's free. */
    private boolean rfb$tryLockTransforms() {
        if (rfb$transformOwner.compareAndSet(null, Thread.currentThread())) {
            rfb$transformDepth = 1;
            return true;
        }
        return false;
    }

    private void rfb$unlockTransforms(final ClassPreloader<rfb$PreparedClass> preloader) {
        if (--rfb$transformDepth > 0) {
            return;
        }
        rfb$transformOwner.set(null);
        if (rfb$waiters > 0) {
            synchronized (this) {
                notifyAll();
            }
        }
        preloader.transformLockReleased();
    }

    /** Must hold this loader's lock; releases it while waiting. Returns whether the thread was interrupted. */
    private boolean rfb$waitOnLoader() {
        boolean interrupted = false;
        rfb$waiters++;
        try {
            wait(20);
        } catch (InterruptedException e) {
            interrupted = true;
        } finally {
            rfb$waiters--;
        }
        return interrupted;
    }

    /**
     * RFB: A class load on the preload worker, triggered by a transformer while it prepares another class (the worker
     * holds the transformer lock and, here, this loader's lock). Defining a class this early is only safe if it gets
     * exactly the bytes it got in the previous launch, and if no other thread is loading it (that thread may be
     * waiting for the transformer lock). Otherwise nothing is defined: the transformer gets a ClassNotFoundException
     * and the class being prepared is discarded, so the loading thread loads both normally later.
     */
    private Class<?> rfb$findClassOnWorker(
            final ClassPreloader<rfb$PreparedClass> preloader, final String name, final boolean runTransformers)
            throws ClassNotFoundException {
        if (rfb$transformOwner.get() != Thread.currentThread() || rfb$inFlight.containsKey(name)) {
            throw rfb$refuseOnWorker(preloader, name, null);
        }
        final String transformedName = runTransformers ? transformName(name) : name;
        {
            final Class<?> defined = cachedClasses.get(transformedName);
            if (defined != null) {
                return defined;
            }
        }
        if (rfb$inFlight.containsKey(transformedName)) {
            throw rfb$refuseOnWorker(preloader, name, null);
        }
        rfb$PreparedClass prepared = preloader.take(name);
        if (prepared != null && prepared.runTransformers != runTransformers) {
            prepared = null;
        }
        if (prepared == null) {
            prepared = rfb$prepare(name, runTransformers, transformedName, true);
            if (prepared == null || !preloader.matchesLastLaunch(transformedName, prepared.classBytes)) {
                throw rfb$refuseOnWorker(preloader, name, null);
            }
        }
        // Load the supertypes through loadClass first, so that defineClass finds them loaded. Otherwise the JVM loads
        // them itself, and would wait for another thread's in-progress load of the same class, which may be waiting
        // for the transformer lock this thread holds.
        final com.gtnewhorizons.retrofuturabootstrap.api.ClassHeaderMetadata header =
                com.gtnewhorizons.retrofuturabootstrap.api.ClassHeaderMetadata.of(prepared.classBytes);
        if (header == null) {
            throw rfb$refuseOnWorker(preloader, name, null);
        }
        final List<String> supertypes = new ArrayList<>(header.binaryInterfaceNames);
        if (header.binarySuperName != null) {
            supertypes.add(0, header.binarySuperName);
        }
        for (String supertype : supertypes) {
            try {
                loadClass(supertype.replace('/', '.'));
            } catch (ClassNotFoundException | LinkageError e) {
                throw rfb$refuseOnWorker(preloader, name, e);
            }
        }
        try {
            return rfb$define(prepared);
        } catch (LinkageError e) {
            throw rfb$refuseOnWorker(preloader, name, e);
        }
    }

    private static ClassNotFoundException rfb$refuseOnWorker(
            ClassPreloader<rfb$PreparedClass> preloader, String name, Throwable cause) {
        preloader.taint();
        return new ClassNotFoundException(name + " can't be loaded early by the class preloader", cause);
    }

    /** RFB: A class read and transformed, but not defined yet */
    private static final class rfb$PreparedClass implements ClassPreloader.Prepared {
        final String transformedName;
        final boolean runTransformers;
        final String packageName;
        /** Whether the package still has to be defined/verified, and with which manifest and URL */
        final boolean packagePending;

        final Manifest packageManifest;
        final URL packageSourceUrl;
        final CodeSource codeSource;
        final byte[] classBytes;

        rfb$PreparedClass(
                String transformedName,
                boolean runTransformers,
                String packageName,
                boolean packagePending,
                Manifest packageManifest,
                URL packageSourceUrl,
                CodeSource codeSource,
                byte[] classBytes) {
            this.transformedName = transformedName;
            this.runTransformers = runTransformers;
            this.packageName = packageName;
            this.packagePending = packagePending;
            this.packageManifest = packageManifest;
            this.packageSourceUrl = packageSourceUrl;
            this.codeSource = codeSource;
            this.classBytes = classBytes;
        }

        @Override
        public String key() {
            return transformedName;
        }

        @Override
        public byte[] bytes() {
            return classBytes;
        }
    }

    private Class<?> rfb$define(rfb$PreparedClass prepared) {
        if (prepared.packagePending) {
            getAndVerifyPackage(prepared.packageName, prepared.packageManifest, prepared.packageSourceUrl);
        }
        final byte[] classBytes = prepared.classBytes;
        Class<?> result;
        try {
            result = defineClass(prepared.transformedName, classBytes, 0, classBytes.length, prepared.codeSource);
        } catch (LinkageError e) {
            // with preloading, only if rfb$inFlight missed a case: the class exists already, use it
            final Class<?> existing = findLoadedClass(prepared.transformedName);
            if (existing == null) {
                throw e;
            }
            LogWrapper.rfb$logger.warn(
                    "Class preloader: {} was defined twice, using the first", prepared.transformedName);
            cachedClasses.put(prepared.transformedName, existing);
            return existing;
        }
        cachedClasses.put(prepared.transformedName, result);
        rfb$preloader.onDefined(prepared.transformedName, classBytes);
        return result;
    }

    /**
     * RFB: Finds, reads and transforms a class, the part of findClass before defining it. With forPreload, the
     * package is defined later (in rfb$define), and failures return null instead of throwing or marking the class
     * invalid, so the loading thread retries it and reports errors as before.
     */
    private rfb$PreparedClass rfb$prepare(
            final String name, final boolean runTransformers, final String transformedName, final boolean forPreload)
            throws ClassNotFoundException {
        final String untransformedName = runTransformers ? untransformName(name) : name;
        final int lastDot = untransformedName.lastIndexOf('.');
        final String packageName = (lastDot == -1) ? "" : untransformedName.substring(0, lastDot);
        final String classPath = untransformedName.replace('.', '/') + ".class";
        final URLConnection connection = findCodeSourceConnectionFor(classPath);
        Package pkg = null;
        boolean packagePending = false;
        Manifest packageManifest = null;
        URL packageUrl = null;
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
                    if (forPreload) {
                        packagePending = true;
                        packageManifest = manifest;
                        packageUrl = packageSourceUrl;
                    } else {
                        pkg = getAndVerifyPackage(packageName, manifest, packageSourceUrl);
                    }
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
                codeSource = connection == null ? null : new CodeSource(connection.getURL(), (CodeSigner[]) null);
            }
        } else {
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
        if (forPreload && (classBytes == null || rfb$preloader.mustNotPreload(classBytes))) {
            return null;
        }
        if (runTransformers) {
            try {
                classBytes = runTransformers(untransformedName, transformedName, classBytes);
            } catch (Throwable t) {
                if (forPreload) {
                    return null;
                }
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
                    if (forPreload) {
                        return null;
                    }
                    ClassNotFoundException err =
                            new ClassNotFoundException("Exception caught while transforming class " + name, t);
                    LogWrapper.rfb$logger.debug("Transformer error", err);
                    throw err;
                }
            }
        }
        if (classBytes == null) {
            if (forPreload) {
                return null;
            }
            invalidClasses.add(name);
            throw new ClassNotFoundException(String.format("Class bytes are null for %s (%s, %s)", name, name, name));
        }
        if (Main.cfgDumpLoadedClasses) {
            Main.dumpClass(this.getClassLoaderName(), transformedName, classBytes);
        }
        if (!packageName.isEmpty() && pkg == null && !packagePending) {
            packagePending = true; // defined in rfb$define, right before the class, as before
        }
        return new rfb$PreparedClass(
                transformedName,
                runTransformers,
                packageName,
                packagePending,
                packageManifest,
                packageUrl,
                codeSource,
                classBytes);
    }

    /**
     * RFB: For the preloader: prepares a class by name, as findClass would. Called by the preload worker holding the
     * transformer lock (not this loader's lock). Returns null if the class is loaded already, handled elsewhere, or
     * failed.
     */
    private rfb$PreparedClass rfb$prepareForPreload(final String name) {
        if (invalidClasses.contains(name) || cachedClasses.containsKey(name)) {
            return null;
        }
        for (final String exception : classLoaderExceptions) {
            if (name.startsWith(exception)) {
                return null;
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
        if (cachedClasses.containsKey(transformedName)) {
            return null;
        }
        try {
            return rfb$prepare(name, runTransformers, transformedName, true);
        } catch (Throwable t) {
            return null;
        }
    }

    /** RFB: The class preloader */
    private final ClassPreloader<rfb$PreparedClass> rfb$preloader = new ClassPreloader<>(
            new ClassPreloader.Target<rfb$PreparedClass>() {
                @Override
                public rfb$PreparedClass prepare(String name) {
                    return rfb$prepareForPreload(name);
                }

                @Override
                public String signature() {
                    return rfb$signature();
                }

                @Override
                public boolean tryLockTransforms() {
                    return rfb$tryLockTransforms();
                }

                @Override
                public void unlockTransforms() {
                    rfb$unlockTransforms(rfb$preloader);
                }
            },
            new File("rfb-preload-order.txt"));

    private volatile java.lang.reflect.Field rfb$mixinRegisteredConfigs;
    private volatile Method rfb$mixinUnvisitedCount;
    private volatile boolean rfb$mixinLookupDone;

    /**
     * RFB: Describes the class transformer setup: transformer counts, and the number of mixin configs registered and
     * not processed yet. Prepared classes are only used while it stays the same. Exclusions aren't part of it: they
     * only matter for the classes they cover, which is checked per class (rfb$PreparedClass.runTransformers).
     */
    private String rfb$signature() {
        final StringBuilder sb = new StringBuilder();
        sb.append(transformers.size())
                .append('/')
                .append(Main.getRfbTransformers().size())
                .append('/')
                .append(renameTransformer != null)
                .append("/mixins:");
        if (!rfb$mixinLookupDone) {
            // may run on two threads at once; both find the same values, and the flag is set last
            for (IClassTransformer xformer : transformers) {
                if (xformer.getClass().getName().equals("org.spongepowered.asm.mixin.transformer.Proxy")) {
                    try {
                        final Class<?> mixins = Class.forName(
                                "org.spongepowered.asm.mixin.Mixins",
                                false,
                                xformer.getClass().getClassLoader());
                        final java.lang.reflect.Field registered = mixins.getDeclaredField("registeredConfigs");
                        registered.setAccessible(true);
                        rfb$mixinUnvisitedCount = mixins.getMethod("getUnvisitedCount");
                        rfb$mixinRegisteredConfigs = registered;
                    } catch (Throwable t) {
                        LogWrapper.rfb$logger.warn("Class preloader: can't count mixin configs", t);
                    }
                    rfb$mixinLookupDone = true;
                    break;
                }
            }
        }
        try {
            if (rfb$mixinRegisteredConfigs == null) {
                sb.append("none");
            } else {
                sb.append(((Collection<?>) rfb$mixinRegisteredConfigs.get(null)).size())
                        .append(" unvisited:")
                        .append(rfb$mixinUnvisitedCount.invoke(null));
            }
        } catch (Throwable t) {
            sb.append('?');
        }
        return sb.toString();
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
            final URL url = rfb$findClassFile(name);
            if (url == null) {
                return null;
            }
            return url.openConnection();
        } catch (Exception e) {
            LogWrapper.rfb$logger.debug("Couldn't findCodeSourceConnectionFor {}: {}", name, e.getMessage());
            return null;
        }
    }

    /** RFB: The classpath as of construction; addURL/addSilentURL append to it */
    private final ClassPathIndex rfb$classPathIndex = new ClassPathIndex(getURLs());

    /** RFB: findResource for class files, answered from the classpath index where it can */
    private URL rfb$findClassFile(final String path) {
        final Object indexed = rfb$classPathIndex.lookup(path);
        return indexed == ClassPathIndex.UNKNOWN ? findResource(path) : (URL) indexed;
    }

    /**
     * <ol>
     *     <li>For each transformer on the transformer list, transform basicClass</li>
     *     <li>Return the updated basicClass</li>
     * </ol>
     */
    private byte[] runTransformers(final String name, final String transformedName, byte[] basicClass) {
        if (rfb$transformOwner.get() == Thread.currentThread()) {
            return rfb$runTransformers(name, transformedName, basicClass);
        }
        // some mods call this private method by reflection
        rfb$lockTransformsExternal();
        try {
            return rfb$runTransformers(name, transformedName, basicClass);
        } finally {
            rfb$unlockTransforms(rfb$preloader);
        }
    }

    private void rfb$lockTransformsExternal() {
        synchronized (this) {
            rfb$lockTransformsWaiting();
        }
    }

    private static final Map<String, Method> rfb$lockedMethods = new ConcurrentHashMap<>();

    /**
     * RFB: Replaces the bodies of some Mixin methods (see MixinTransformLockTransformer) that run the LaunchClassLoader
     * transformers or change the mixin configs that transforming a class reads, possibly outside any class load. They
     * run holding the transformer lock, so they never run while the preload worker transforms a class.
     * Calls the original method, renamed to {@code name}, on {@code self}.
     */
    public static Object rfb$invokeLocked(final Object self, final String name, final Object[] args) throws Throwable {
        final String key = self.getClass().getName() + '#' + name;
        Method original = rfb$lockedMethods.get(key);
        if (original == null) {
            for (Method m : self.getClass().getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    m.setAccessible(true);
                    original = m;
                    break;
                }
            }
            if (original == null) {
                throw new IllegalStateException(name + " not found in " + self.getClass());
            }
            rfb$lockedMethods.put(key, original);
        }
        final LaunchClassLoader lcl = Launch.classLoader;
        final boolean lock = lcl != null && lcl.rfb$transformOwner.get() != Thread.currentThread();
        if (lock) {
            lcl.rfb$lockTransformsExternal();
        }
        try {
            return original.invoke(self, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        } finally {
            if (lock) {
                lcl.rfb$unlockTransforms(lcl.rfb$preloader);
            }
        }
    }

    private byte[] rfb$runTransformers(final String name, final String transformedName, byte[] basicClass) {
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
        this.negativeResourceCache.clear();
        super.addURL(url);
        rfb$classPathIndex.add(url);
        sources.add(url);
    }

    @Override
    public void addSilentURL(@Nullable URL url) {
        this.negativeResourceCache.clear();
        super.addURL(url);
        rfb$classPathIndex.add(url);
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
                return ClassHeaderMetadata.of(classBytes);
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
        final URL resourceUrl = rfb$findClassFile(classPath);
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
