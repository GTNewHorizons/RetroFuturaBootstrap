package com.gtnewhorizons.retrofuturabootstrap;

import com.gtnewhorizons.retrofuturabootstrap.api.ClassNodeHandle;
import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import com.gtnewhorizons.retrofuturabootstrap.api.SimpleClassTransformer;
import com.gtnewhorizons.retrofuturabootstrap.api.SimpleClassTransformerHandle;
import java.net.URL;
import java.net.URLStreamHandlerFactory;
import java.util.Collection;

/**
 * Non-Java-version-specific extensions to {@link URLClassLoaderBase}
 */
public class URLClassLoaderWithUtilities extends URLClassLoaderBase {
    public URLClassLoaderWithUtilities(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    public URLClassLoaderWithUtilities(URL[] urls) {
        super(urls);
    }

    public URLClassLoaderWithUtilities(URL[] urls, ClassLoader parent, URLStreamHandlerFactory factory) {
        super(urls, parent, factory);
    }

    public URLClassLoaderWithUtilities(String name, URL[] urls, ClassLoader parent) {
        super(name, urls, parent);
    }

    public URLClassLoaderWithUtilities(String name, URL[] urls, ClassLoader parent, URLStreamHandlerFactory factory) {
        super(name, urls, parent, factory);
    }

    public byte[] runCompatibilityTransformers(
            final Collection<SimpleClassTransformerHandle> compatibilityTransformers,
            final SimpleClassTransformer.Context context,
            final String className,
            byte[] basicClass) {
        if (compatibilityTransformers.isEmpty()) {
            return basicClass;
        }
        final ExtensibleClassLoader self = (ExtensibleClassLoader) this;
        int xformerIndex = 0;
        final ClassNodeHandle nodeHandle = new ClassNodeHandle(basicClass);
        xformerLoop:
        for (SimpleClassTransformerHandle handle : compatibilityTransformers) {
            for (final String exclusion : handle.exclusions()) {
                if (className.startsWith(exclusion)) {
                    continue xformerLoop;
                }
            }
            final SimpleClassTransformer xformer = handle.transformer();
            try {
                if (xformer.shouldTransformClass(self, context, className, basicClass)) {
                    xformer.transformClass(self, context, className, nodeHandle);

                    if (Main.cfgDumpLoadedClassesPerTransformer) {
                        final byte[] newBytes = nodeHandle.computeBytes();
                        if (newBytes != null) {
                            Main.dumpClass(
                                    this.getClassLoaderName(),
                                    String.format("%s__S%03d_%s", className, xformerIndex, xformer.id()),
                                    newBytes);
                        }
                    }
                }
            } catch (UnsupportedOperationException e) {
                if (e.getMessage().contains("requires ASM")) {
                    Main.logger.warn(
                            "ASM transformer {} encountered a newer classfile ({}) than supported: {}",
                            xformer.getClass().getName(),
                            className,
                            e.getMessage());
                    xformerIndex++;
                    continue;
                }
                throw e;
            }
            xformerIndex++;
        }
        return nodeHandle.computeBytes();
    }
}
