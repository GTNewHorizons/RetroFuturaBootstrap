package com.gtnewhorizons.retrofuturabootstrap;

import com.gtnewhorizons.retrofuturabootstrap.api.ClassHeaderMetadata;
import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import com.gtnewhorizons.retrofuturabootstrap.api.FastClassAccessor;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.jar.Manifest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A simpler, non-renaming version of {@link net.minecraft.launchwrapper.LaunchClassLoader} used for loading all coremod classes.
 * Allows for RFB class transformers to run just before class definition happens on all modded classes, including coremods.
 */
public final class RfbSystemClassLoader extends URLClassLoaderWithUtilities implements ExtensibleClassLoader {

    static {
        ClassLoader.registerAsParallelCapable();
    }

    /** A public hook to allow other parent classloaders to repeat any addURL calls as needed. Only useful if you're loading RFB as a child loader in another platform. */
    public static Consumer<URL> addURLHook = null;
    /** A reference to the classloader that loaded this class */
    private ClassLoader parent = getClass().getClassLoader();
    /** Reference to the platform class loader that can load JRE/JDK classes */
    private static final ClassLoader platformLoader = getPlatformClassLoader();
    /** Reference to the child LaunchClassLoader, used to lookup cached classes to avoid duplicate loading. */
    private ExtensibleClassLoader childLoader = null;

    /** A ConcurrentHashMap cache of all classes loaded via this loader */
    private final Map<String, WeakReference<Class<?>>> cachedClasses = new ConcurrentHashMap<>();
    /** A ConcurrentHashMap cache of class bytes loaded via this classloader */
    private final Map<String, SoftReference<byte[]>> resourceCache = new ConcurrentHashMap<>(1000);

    /**
     * A HashSet of all class prefixes (e.g. "java.") to globally redirect to the parent classloader.
     * Use sparingly, the RFB plugin transformers have a very flexible per-transformer exclusion system.
     */
    private Set<String> classLoaderExceptions = new HashSet<>();
    /**
     * A HashSet of all class prefixes (e.g. "my.child.package.") to redirect to the child classloader.
     */
    public Set<String> childDelegations = new HashSet<>();

    /** A dummy, empty manifest field */
    private static final Manifest EMPTY = new Manifest();

    /**
     * @param sources The initial classpath
     */
    public RfbSystemClassLoader(String name, URL[] sources) {
        super(name, sources, getPlatformClassLoader());
        classLoaderExceptions.addAll(Arrays.asList(
                "java.",
                "jdk.internal.",
                "sun.",
                "org.apache.logging.",
                "org.objectweb.asm.",
                "LZMA.",
                "org.slf4j.",
                "com.gtnewhorizons.retrofuturabootstrap."));
    }

    /** Invoked by Java itself */
    public RfbSystemClassLoader(ClassLoader parent) throws ReflectiveOperationException {
        this("System", getUrlClasspathEntries(parent));
        Thread.currentThread().setContextClassLoader(this);
    }

    /** Registers a fresh LaunchClassLoader as the child of this loader. */
    public void setChildLoader(ExtensibleClassLoader ecl) {
        childLoader = ecl;
    }

    public ExtensibleClassLoader getChildLoader() {
        if (childLoader == null) {
            try {
                Class<?> lclClass = Class.forName("net.minecraft.launchwrapper.LaunchClassLoader", true, this);
                setChildLoader((ExtensibleClassLoader)
                        lclClass.getConstructor(URL[].class).newInstance((Object) getURLs()));
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
        return childLoader;
    }

    public static @NotNull URL[] getUrlClasspathEntries(ClassLoader appClassLoader) {
        if (appClassLoader instanceof URLClassLoader) {
            final List<URL> appUrls = Arrays.asList(((URLClassLoader) appClassLoader).getURLs());
            Collections.reverse(appUrls);
            final ArrayList<URL> urlSet = new ArrayList<>(appUrls);
            for (ClassLoader parent = appClassLoader.getParent(); parent != null; parent = parent.getParent()) {
                if (parent instanceof URLClassLoader) {
                    final List<URL> parentUrls = Arrays.asList(((URLClassLoader) parent).getURLs());
                    Collections.reverse(parentUrls);
                    urlSet.addAll(parentUrls);
                }
            }
            // [3 2 1] [6 5 4] -> [4 5 6] [1 2 3] - reverse the order of loaders, while keeping the order inside each
            // loader
            Collections.reverse(urlSet);
            return urlSet.toArray(new URL[0]);
        }
        return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(path -> {
                    try {
                        return new File(path).toURI().toURL();
                    } catch (MalformedURLException e) {
                        System.err.printf("Could not parse %s into an URL%n%s%n", path, e.getMessage());
                        e.printStackTrace(System.err);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toArray(URL[]::new);
    }

    @Override
    public Class<?> findCachedClass(final String name) {
        final WeakReference<Class<?>> cached = cachedClasses.get(name);
        if (cached != null) {
            return cached.get();
        }
        return null;
    }

    @Override
    public @Nullable FastClassAccessor findClassMetadata(@NotNull String name) {
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

    private final ThreadLocal<HashSet<String>> isDelegatingToChild = new ThreadLocal<>();
    /**
     * Find/load a class by name
     */
    @Override
    public @NotNull Class<?> findClass(final @NotNull String name) throws ClassNotFoundException {
        for (final String exception : classLoaderExceptions) {
            if (name.startsWith(exception)) {
                return parent.loadClass(name);
            }
        }
        HashSet<String> isDelegatingToChild = this.isDelegatingToChild.get();
        if (isDelegatingToChild == null) {
            isDelegatingToChild = new HashSet<>();
            this.isDelegatingToChild.set(isDelegatingToChild);
        }
        if (isDelegatingToChild.contains(name)) {
            throw new ClassNotFoundException(name);
        }
        for (final String delegation : childDelegations) {
            if (name.startsWith(delegation)) {
                boolean wasAdded = isDelegatingToChild.add(name);
                try {
                    return ((URLClassLoader) getChildLoader()).loadClass(name);
                } finally {
                    if (wasAdded) {
                        isDelegatingToChild.remove(name);
                    }
                }
            }
        }
        {
            final WeakReference<Class<?>> cached = cachedClasses.get(name);
            if (cached != null) {
                final Class<?> cachedStrong = cached.get();
                if (cachedStrong != null) {
                    return cachedStrong;
                }
            }
        }
        final int lastDot = name.lastIndexOf('.');
        final String packageName = (lastDot == -1) ? "" : name.substring(0, lastDot);
        final String classPath = name.replace('.', '/') + ".class";
        final URLConnection connection = findCodeSourceConnectionFor(classPath);
        Package pkg = null;
        final CodeSource codeSource;
        Manifest manifest = null;
        byte[] classBytes = null;
        if (!packageName.isEmpty()) {
            if (!name.startsWith("net.minecraft.") && connection instanceof JarURLConnection) {
                final JarURLConnection jarConnection = (JarURLConnection) connection;
                final URL codeSourceUrl = jarConnection.getJarFileURL();
                CodeSigner[] codeSigners = null;
                try {
                    manifest = jarConnection.getManifest();
                    pkg = getAndVerifyPackage(packageName, manifest, codeSourceUrl);
                    classBytes = getClassBytes(name);
                    codeSigners = jarConnection.getJarEntry().getCodeSigners();
                } catch (IOException e) {
                    // no-op
                }
                // Different from LaunchClassLoader to mimic Java ClassLoaders.
                codeSource = new CodeSource(codeSourceUrl, codeSigners);
            } else {
                codeSource = connection == null ? null : new CodeSource(connection.getURL(), (CodeSigner[]) null);
            }
        } else {
            codeSource = null;
        }
        if (classBytes == null) {
            try {
                classBytes = getClassBytes(name);
            } catch (IOException e) {
                /* no-op */
            }
        }
        try {
            if (SharedConfig.cfgDumpLoadedClassesPerTransformer && classBytes != null) {
                SharedConfig.dumpClass(this.getClassLoaderName(), name + "_000_pretransform", classBytes);
            }
            classBytes = runRfbTransformers(
                    SharedConfig.getRfbTransformers(), RfbClassTransformer.Context.SYSTEM, manifest, name, classBytes);
        } catch (Throwable t) {
            ClassNotFoundException err =
                    new ClassNotFoundException("Exception caught while transforming class " + name, t);
            SharedConfig.logDebug("Transformer error", err);
            throw err;
        }
        if (classBytes == null) {
            throw new ClassNotFoundException(String.format("Class bytes are null for %s (%s, %s)", name, name, name));
        }
        if (!packageName.isEmpty() && pkg == null) {
            getAndVerifyPackage(packageName, null, null);
        }
        if (SharedConfig.cfgDumpLoadedClasses) {
            SharedConfig.dumpClass(this.getClassLoaderName(), name, classBytes);
        }
        Class<?> result = defineClass(name, classBytes, 0, classBytes.length, codeSource);
        cachedClasses.put(name, new WeakReference<>(result));
        return result;
    }

    // based off OpenJDK's own URLClassLoader
    public Package getAndVerifyPackage(final String packageName, final Manifest manifest, final URL codeSourceURL) {
        return super.getAndVerifyPackage(packageName, manifest, codeSourceURL);
    }

    /**
     * Checks the manifest path SEALED attribute, then checks the main attributes for the sealed property. Returns if
     * present and equal to "true" ignoring case.
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
            SharedConfig.logDebug("Couldn't findCodeSourceConnectionFor " + name, e);
            return null;
        }
    }

    /**
     * Adds the given url to the classpath (via super) and the sources field.
     */
    @Override
    public void addURL(final URL url) {
        super.addURL(url);
        if (addURLHook != null) {
            addURLHook.accept(url);
        }
    }

    @Override
    public void addSilentURL(@Nullable URL url) {
        super.addURL(url);
        if (addURLHook != null) {
            addURLHook.accept(url);
        }
    }

    /**
     * Required for Java Agents to work on HotSpot
     * @param path The file path added to the classpath
     */
    @SuppressWarnings("unused")
    public void appendToClassPathForInstrumentation(String path) {
        try {
            addURL(new File(path).toURI().toURL());
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Returns the saved classpath list */
    public List<URL> getSources() {
        return Arrays.asList(super.getURLs());
    }

    /**
     * Tries to fully read the input stream into a byte array<strike>, using getOrCreateBuffer() as the IO
     * buffer</strike>. Returns an empty array and logs a warning if any exception happens.
     */
    private byte[] readFully(InputStream stream) {
        try {
            return readAllBytes(stream, null);
        } catch (Exception e) {
            SharedConfig.logWarning("Could not read InputStream " + stream, e);
            return new byte[0];
        }
    }

    /** Adds a new entry to the end of the class loader exclusions list */
    public void addClassLoaderExclusion(String toExclude) {
        classLoaderExceptions.add(toExclude);
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
        final SoftReference<byte[]> cached = resourceCache.get(name);
        if (cached != null) {
            final byte[] cachedStrong = cached.get();
            if (cachedStrong != null) {
                return cachedStrong.clone();
            }
        }
        final String classPath = name.replace('.', '/') + ".class";
        final URL resourceUrl = findResource(classPath);
        URLConnection conn = resourceUrl == null ? null : resourceUrl.openConnection();
        if (conn == null && platformLoader != null) {
            // Try JRE classes
            final URL platformUrl = platformLoader.getResource(classPath);
            if (platformUrl != null) {
                conn = platformUrl.openConnection();
            }
        }
        if (conn == null) {
            return null;
        }
        final InputStream is = conn.getInputStream();
        final byte[] contents = readFully(is);
        closeSilently(is);
        if (contents == null) {
            return null;
        }
        resourceCache.put(name, new SoftReference<>(contents));
        return contents.clone();
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

    @Override
    public @NotNull URLClassLoader asURLClassLoader() {
        return this;
    }
}
