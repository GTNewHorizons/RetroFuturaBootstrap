package com.gtnewhorizons.retrofuturabootstrap;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * An index from package directory to the classpath jars containing it.
 * <p>
 * The answer is the same as {@link java.net.URLClassLoader#findResource(String)} returns, but much faster
 * (O(1) instead of O(n)).
 * <p>
 * Thread-safe: the class preloader's worker looks up class files without holding the loader's lock, while other
 * threads look up classes and add classpath entries.
 */
public final class ClassPathIndex {
    /** Returned by {@link #lookup} when the caller has to use the regular, linear lookup. */
    public static final Object UNKNOWN = new Object();

    private static final int[] NO_POSITIONS = new int[0];
    private static final String VERSIONS = "META-INF/versions/";

    private final int javaMajor = URLClassLoaderBase.getJavaMajorVersion();
    private final List<Jar> jars = new ArrayList<>();
    private final Set<String> knownUrls = new HashSet<>();
    private final Map<String, int[]> packages = new HashMap<>();
    /** Position from which on the classpath search order isn't known; Integer.MAX_VALUE if all of it is known. */
    private int barrier = Integer.MAX_VALUE;

    private static final class Jar {
        final URL base;
        final JarFile file;
        /**
         * For multi-release jars: path -> real entry name of the highest META-INF/versions/N copy the runtime uses.
         * The JDK returns that copy, and its URL names the versioned entry.
         */
        final Map<String, String> versioned;

        Jar(URL base, JarFile file, Map<String, String> versioned) {
            this.base = base;
            this.file = file;
            this.versioned = versioned;
        }
    }

    public ClassPathIndex(URL[] urls) {
        for (URL url : urls) {
            add(url);
        }
    }

    /** Appends a classpath entry, like {@link java.net.URLClassLoader#addURL(URL)}. Duplicates are ignored. */
    public synchronized void add(URL url) {
        if (url == null || !knownUrls.add(url.toExternalForm())) {
            return;
        }
        final int position = jars.size();
        if (barrier != Integer.MAX_VALUE) {
            jars.add(null);
            return;
        }
        final File file = toFile(url);
        if (file == null || file.isDirectory()) {
            jars.add(null);
            barrier = position;
            return;
        }
        if (!file.exists()) {
            jars.add(null); // the JDK skips classpath jars that can't be opened
            return;
        }
        final JarFile jarFile;
        final Manifest manifest;
        try {
            jarFile = new JarFile(file, false, ZipFile.OPEN_READ);
            manifest = jarFile.getManifest();
        } catch (IOException | RuntimeException e) {
            jars.add(null);
            barrier = position;
            return;
        }
        final boolean multiRelease = manifest != null
                && "true".equalsIgnoreCase(manifest.getMainAttributes().getValue("Multi-Release"))
                && javaMajor >= 9;
        Map<String, String> versioned = null;
        Map<String, Integer> versionOf = null;
        final Set<String> addedPackages = new HashSet<>();
        final Enumeration<? extends ZipEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.endsWith("/")) {
                continue;
            }
            if (multiRelease && name.startsWith(VERSIONS)) {
                final int slash = name.indexOf('/', VERSIONS.length());
                if (slash < 0) {
                    continue;
                }
                final int version;
                try {
                    version = Integer.parseInt(name.substring(VERSIONS.length(), slash));
                } catch (NumberFormatException e) {
                    continue;
                }
                if (version > javaMajor) {
                    continue;
                }
                final String realName = name;
                name = name.substring(slash + 1);
                if (versioned == null) {
                    versioned = new HashMap<>();
                    versionOf = new HashMap<>();
                }
                final Integer previous = versionOf.get(name);
                if (previous == null || previous < version) {
                    versionOf.put(name, version);
                    versioned.put(name, realName);
                }
            }
            final String pkg = name.substring(0, name.lastIndexOf('/') + 1);
            if (addedPackages.add(pkg)) {
                final int[] old = packages.getOrDefault(pkg, NO_POSITIONS);
                final int[] updated = Arrays.copyOf(old, old.length + 1);
                updated[old.length] = position;
                packages.put(pkg, updated);
            }
        }
        final URL base;
        try {
            base = new URL("jar", "", -1, url + "!/");
        } catch (IOException e) {
            closeQuietly(jarFile);
            jars.add(null);
            barrier = position;
            return;
        }
        jars.add(new Jar(base, jarFile, versioned));
        if (manifest != null && addsJarsToSearch(manifest, url)) {
            barrier = position + 1;
        }
    }

    /**
     * @return the URL {@link java.net.URLClassLoader#findResource(String)} would return, null if it would return null,
     *     or {@link #UNKNOWN}
     */
    public synchronized Object lookup(String path) {
        final int[] positions = packages.getOrDefault(path.substring(0, path.lastIndexOf('/') + 1), NO_POSITIONS);
        for (int position : positions) {
            if (position >= barrier) {
                return UNKNOWN;
            }
            final Jar jar = jars.get(position);
            String entry = jar.versioned == null ? null : jar.versioned.get(path);
            if (entry == null && jar.file.getEntry(path) != null) {
                entry = path;
            }
            if (entry != null) {
                try {
                    return new URL(jar.base, entry);
                } catch (IOException e) {
                    return UNKNOWN;
                }
            }
        }
        return barrier == Integer.MAX_VALUE ? null : UNKNOWN;
    }

    /** Whether a manifest Class-Path entry names an existing file that isn't on the classpath yet. */
    private boolean addsJarsToSearch(Manifest manifest, URL jarUrl) {
        final String classPath = manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
        if (classPath == null || classPath.trim().isEmpty()) {
            return false;
        }
        for (String entry : classPath.trim().split("\\s+")) {
            try {
                final URL resolved = new URL(jarUrl, entry);
                if (knownUrls.contains(resolved.toExternalForm())) {
                    continue;
                }
                final File file = toFile(resolved);
                if (file == null || file.exists()) {
                    return true;
                }
            } catch (IOException | RuntimeException e) {
                return true;
            }
        }
        return false;
    }

    private static File toFile(URL url) {
        if (!"file".equalsIgnoreCase(url.getProtocol())) {
            return null;
        }
        try {
            return new File(url.toURI());
        } catch (Exception e) {
            try {
                return new File(URI.create(url.toExternalForm().replace(" ", "%20")));
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private static void closeQuietly(JarFile file) {
        try {
            file.close();
        } catch (IOException e) {
            // no-op
        }
    }
}
