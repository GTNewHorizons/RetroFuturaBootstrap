package com.gtnewhorizons.retrofuturabootstrap.api;

import java.net.URL;
import java.net.URLClassLoader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Accessor interface implemented on RFB's ClassLoader instances to allow accessing some normally-protected methods.
 */
@SuppressWarnings("unused") // this class provides an API
public interface ExtensibleClassLoader {

    /**
     * @return this as a {@link URLClassLoader}, all implementations of this interface are classes extending URLClassLoader.
     */
    @NotNull
    URLClassLoader asURLClassLoader();

    /**
     * Returns the name of this class loader or {@code null} if
     * this class loader is not named.
     *
     * @return name of this class loader; or {@code null} if
     * this class loader is not named.
     */
    @Nullable
    String getClassLoaderName();

    /**
     * Appends the specified URL to the list of URLs to search for
     * classes and resources.
     * <p>
     * If the URL specified is {@code null} or is already in the
     * list of URLs, or if this loader is closed, then invoking this
     * method has no effect.
     *
     * @param url the URL to be added to the search path of URLs
     */
    void addURL(@Nullable URL url);

    /**
     * Like addURL, but does not add the URL to the sources array.
     *
     * @param url the URL to be added to the search path of URLs
     */
    void addSilentURL(@Nullable URL url);

    /**
     * Finds and loads the class with the specified name from the URL search
     * path. Any URLs referring to JAR files are loaded and opened as needed
     * until the class is found.
     *
     * @param     name the name of the class
     * @return    the resulting class
     * @throws    ClassNotFoundException if the class could not be found,
     *            or if the loader is closed.
     * @throws    NullPointerException if {@code name} is {@code null}.
     */
    @NotNull
    Class<?> findClass(final @NotNull String name) throws ClassNotFoundException;

    /**
     * Check the cache for a class that already has been loaded
     * @param name the name of the class
     * @return The loaded class if found, or null if not found
     */
    Class<?> findCachedClass(final String name);

    /**
     * Finds class metadata for a given class if already loaded, or the parsed classfile header if not yet loaded.
     * @param name the name of the class
     * @return The parsed/cached metadata, or null if not found
     */
    @Nullable
    FastClassAccessor findClassMetadata(final @NotNull String name);
}
