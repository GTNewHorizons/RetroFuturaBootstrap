package com.gtnewhorizons.retrofuturabootstrap;

import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import com.gtnewhorizons.retrofuturabootstrap.api.SimpleClassTransformer;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
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
import net.minecraft.launchwrapper.LogWrapper;

/**
 * A simpler, non-renaming version of {@link net.minecraft.launchwrapper.LaunchClassLoader} used for loading all coremod classes.
 * Allows for "compatibility transformers" to run just before class loading happens on all modded classes, including coremods.
 */
public final class SimpleTransformingClassLoader extends URLClassLoaderBase implements ExtensibleClassLoader {

    static {
        ClassLoader.registerAsParallelCapable();
    }

    /** A reference to the classloader that loaded this class */
    private ClassLoader parent = getClass().getClassLoader();
    /** Reference to the platform class loader that can load JRE/JDK classes */
    private static final ClassLoader platformLoader = getPlatformClassLoader();

    /** An ArrayList of all compatibility class transformers used, mutable, in order of application */
    private final List<SimpleClassTransformer> compatibilityTransformers = new ArrayList<>(4);
    /** A ConcurrentHashMap cache of all classes loaded via this loader */
    private final Map<String, WeakReference<Class<?>>> cachedClasses = new ConcurrentHashMap<>();
    /** A ConcurrentHashMap cache of class bytes loaded via this classloader */
    private final Map<String, SoftReference<byte[]>> resourceCache = new ConcurrentHashMap<>(1000);

    /**
     * A HashSet of all class prefixes (e.g. "org.lwjgl.") to redirect to the parent classloader.
     */
    private Set<String> classLoaderExceptions = new HashSet<>();

    /** A dummy, empty manifest field */
    private static final Manifest EMPTY = new Manifest();

    /**
     * @param sources The initial classpath
     */
    public SimpleTransformingClassLoader(String name, URL[] sources) {
        super(name, sources, getPlatformClassLoader());
        LogWrapper.configureLogging();
        classLoaderExceptions.addAll(Arrays.asList(
                "java.",
                "sun.",
                "com.sun.",
                "org.lwjgl.",
                "org.apache.logging.",
                "LZMA.",
                "com.gtnewhorizons.retrofuturabootstrap."));
    }

    /**
     * Find/load a class by name
     */
    @Override
    public Class<?> findClass(final String name) throws ClassNotFoundException {
        for (final String exception : classLoaderExceptions) {
            if (name.startsWith(exception)) {
                return parent.loadClass(name);
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
        final Package pkg;
        final CodeSource codeSource;
        if (!packageName.isEmpty()) {
            if (!name.startsWith("net.minecraft") && connection instanceof JarURLConnection) {
                final JarURLConnection jarConnection = (JarURLConnection) connection;
                final URL codeSourceUrl = jarConnection.getJarFileURL();
                Manifest manifest = null;
                CodeSigner[] codeSigners = null;
                try {
                    manifest = jarConnection.getManifest();
                    pkg = getAndVerifyPackage(packageName, manifest, codeSourceUrl);
                    getClassBytes(name);
                    codeSigners = jarConnection.getJarEntry().getCodeSigners();
                } catch (IOException e) {
                    // no-op
                }
                codeSource = new CodeSource(codeSourceUrl, codeSigners);
            } else {
                pkg = getAndVerifyPackage(packageName, null, null);
                codeSource = connection == null ? null : new CodeSource(connection.getURL(), (CodeSigner[]) null);
            }
        } else {
            pkg = null;
            codeSource = null;
        }
        byte[] classBytes = null;
        try {
            classBytes = getClassBytes(name);
        } catch (IOException e) {
            /* no-op */
        }
        try {
            classBytes = runTransformers(name, classBytes);
        } catch (Throwable t) {
            ClassNotFoundException err =
                    new ClassNotFoundException("Exception caught while transforming class " + name, t);
            LogWrapper.logger.debug("Transformer error", err);
            throw err;
        }
        if (classBytes == null) {
            throw new ClassNotFoundException(String.format("Class bytes are null for %s (%s, %s)", name, name, name));
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
            LogWrapper.logger.debug("Couldn't findCodeSourceConnectionFor {}: {}", name, e.getMessage());
            return null;
        }
    }

    /**
     * <ol>
     *     <li>For each transformer on the transformer list, transform basicClass</li>
     *     <li>Return the updated basicClass</li>
     * </ol>
     */
    private byte[] runTransformers(final String name, byte[] basicClass) {
        for (SimpleClassTransformer xformer : compatibilityTransformers) {
            try {
                final byte[] newKlass = xformer.transformClass(this, name, basicClass);
                // TODO: diff
                basicClass = newKlass;
            } catch (UnsupportedOperationException e) {
                if (e.getMessage().contains("requires ASM")) {
                    LogWrapper.logger.warn(
                            "ASM transformer {} encountered a newer classfile ({}) than supported: {}",
                            xformer.getClass().getName(),
                            name,
                            e.getMessage());
                    continue;
                }
                throw e;
            }
        }
        return basicClass;
    }

    /**
     * Adds the given url to the classpath (via super) and the sources field.
     */
    @Override
    public void addURL(final URL url) {
        super.addURL(url);
    }

    /** Returns the saved classpath list */
    public List<URL> getSources() {
        return Arrays.asList(super.getURLs());
    }

    /** Returns an immutable view of the list of compatibility transformers */
    public List<SimpleClassTransformer> getCompatibilityTransformers() {
        return Collections.unmodifiableList(compatibilityTransformers);
    }

    /** Removes a compatibility transformer by index */
    public void removeCompatibilityTransformer(int index) {
        compatibilityTransformers.remove(index);
    }

    /** Inserts the given compatibility transformers at the given index into the list */
    public void registerCompatibilityTransformers(int index, SimpleClassTransformer... transformers) {
        compatibilityTransformers.addAll(index, Arrays.asList(transformers));
        for (SimpleClassTransformer xformer : transformers) {
            xformer.onRegistration(this);
        }
    }

    /** Inserts the given compatibility transformers at the end of the list */
    public void registerCompatibilityTransformers(SimpleClassTransformer... transformers) {
        registerCompatibilityTransformers(compatibilityTransformers.size() - 1, transformers);
    }

    /**
     * Tries to fully read the input stream into a byte array<strike>, using getOrCreateBuffer() as the IO
     * buffer</strike>. Returns an empty array and logs a warning if any exception happens.
     */
    private byte[] readFully(InputStream stream) {
        try {
            return readAllBytes(stream, null);
        } catch (Exception e) {
            LogWrapper.logger.warn("Could not read InputStream {}", stream.toString(), e);
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
    public URLClassLoader asURLClassLoader() {
        return this;
    }
}
