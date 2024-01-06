package com.gtnewhorizons.retrofuturabootstrap;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Objects;
import net.minecraft.launchwrapper.LogWrapper;

public class Main {
    /** The compatibility ClassLoader that's a parent of LaunchClassLoader. */
    public static SimpleTransformingClassLoader compatLoader;
    /** The ClassLoader that loaded this class. */
    public static final ClassLoader appClassLoader = Main.class.getClassLoader();

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
        URLProtocolFactory.register();
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
        }
    }
}
