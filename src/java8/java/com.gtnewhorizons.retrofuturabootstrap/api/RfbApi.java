package com.gtnewhorizons.retrofuturabootstrap.api;

/**
 * The public Java 8-compatible RetroFuturaBootstrap API
 */
public interface RfbApi {
    /**
     * @return The Java 9+ Platform class loader, that has access to all the classes on the standard library module path.
     */
    ClassLoader platformClassLoader();

    /**
     * @return The Java 9+ System class loader, used for loading bootstrapping java.base classes.
     */
    ClassLoader systemClassLoader();

    /**
     * @return The {@code LaunchClassLoader} responsible for loading Minecraft and mod classes.
     */
    ExtensibleClassLoader launchClassLoader();
}
