package com.gtnewhorizons.retrofuturabootstrap;

import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import com.gtnewhorizons.retrofuturabootstrap.api.SimpleClassTransformer;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLStreamHandlerFactory;
import java.util.Arrays;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import net.minecraft.launchwrapper.LogWrapper;

/**
 * URLClassLoader base class exposing stub Java 9+ APIs in Java 8, and real ones in Java 9+ using a multi-release JAR.
 */
public class URLClassLoaderBase extends URLClassLoader {
    public URLClassLoaderBase(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    public URLClassLoaderBase(URL[] urls) {
        super(urls);
    }

    public URLClassLoaderBase(URL[] urls, ClassLoader parent, URLStreamHandlerFactory factory) {
        super(urls, parent, factory);
    }

    public URLClassLoaderBase(String name, URL[] urls, ClassLoader parent) {
        super(name, urls, parent);
    }

    public URLClassLoaderBase(String name, URL[] urls, ClassLoader parent, URLStreamHandlerFactory factory) {
        super(name, urls, parent, factory);
    }

    public String getClassLoaderName() {
        return super.getName();
    }

    /** null on Java 8 */
    public static ClassLoader getPlatformClassLoader() {
        return ClassLoader.getPlatformClassLoader();
    }

    public Package getDefinedPackageUniversal(String name) {
        return this.getDefinedPackage(name);
    }

    public static Package getDefinedPackageUniversalOf(ClassLoader loader, String name) {
        return loader.getDefinedPackage(name);
    }

    public static byte[] readAllBytes(InputStream stream, byte[] readBuffer) throws IOException {
        return stream.readAllBytes();
    }

    // based off OpenJDK's own URLClassLoader
    public Package getAndVerifyPackage(final String packageName, final Manifest manifest, final URL codeSourceURL) {
        Package pkg = getDefinedPackage(packageName);
        if (pkg == null) {
            pkg = getClass().getClassLoader().getDefinedPackage(packageName);
        }
        if (pkg != null) {
            if (pkg.isSealed() && !pkg.isSealed(codeSourceURL)) {
                throw new SecurityException("Sealing violation in package " + packageName);
            } else if (manifest != null && isSealed(packageName, manifest)) {
                throw new SecurityException("Sealing violation in already loaded package " + packageName);
            }
        } else {
            return definePackage(packageName, new Manifest(), codeSourceURL);
        }
        return pkg;
    }

    /**
     * Checks the manifest path SEALED attribute, then checks the main attributes for the sealed property. Returns if
     * present and equal to "true" ignoring case.
     */
    public boolean isSealed(final String packageName, final Manifest manifest) {
        if (manifest == null) {
            return false;
        }
        final String path = packageName.replace('.', '/') + '/';
        final Attributes attributes = manifest.getAttributes(path);
        if (attributes != null) {
            final String value = attributes.getValue(Attributes.Name.SEALED);
            if (value != null) {
                return value.equalsIgnoreCase("true");
            }
        }
        final Attributes mainAttributes = manifest.getMainAttributes();
        if (mainAttributes != null) {
            final String value = mainAttributes.getValue(Attributes.Name.SEALED);
            if (value != null) {
                return value.equalsIgnoreCase("true");
            }
        }
        return false;
    }

    public byte[] runCompatibilityTransformers(
            final Iterable<SimpleClassTransformer> compatibilityTransformers,
            final SimpleClassTransformer.Context context,
            final String className,
            byte[] basicClass) {
        int xformerIndex = 0;
        for (SimpleClassTransformer xformer : compatibilityTransformers) {
            try {
                final byte[] newKlass =
                        xformer.transformClass((ExtensibleClassLoader) this, context, className, basicClass);
                if (Main.cfgDumpLoadedClassesPerTransformer
                        && newKlass != null
                        && !Arrays.equals(basicClass, newKlass)) {
                    Main.dumpClass(
                            this.getClassLoaderName(),
                            String.format("%s__S%03d_%s", className, xformerIndex, xformer.name()),
                            basicClass);
                }
                basicClass = newKlass;
            } catch (UnsupportedOperationException e) {
                if (e.getMessage().contains("requires ASM")) {
                    LogWrapper.logger.warn(
                            "ASM transformer {} encountered a newer classfile ({}) than supported: {}",
                            xformer.getClass().getName(),
                            className,
                            e.getMessage());
                    continue;
                }
                throw e;
            }
            xformerIndex++;
        }
        return basicClass;
    }
}
