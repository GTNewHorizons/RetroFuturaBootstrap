package com.gtnewhorizons.retrofuturabootstrap.api;

import com.gtnewhorizons.retrofuturabootstrap.RfbSystemClassLoader;
import java.nio.file.Path;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The public Java 8-compatible RetroFuturaBootstrap API
 */
@SuppressWarnings("unused")
public interface RfbApi {
    /**
     * @return The Java 9+ Platform class loader, that has access to all the classes on the standard library module path. null on Java 8.
     */
    @Nullable
    ClassLoader platformClassLoader();

    /**
     * @return The original JVM System class loader, used for loading RFB itself.
     */
    @NotNull
    ClassLoader originalSystemClassLoader();

    /**
     * @return The {@link RfbSystemClassLoader} responsible for loading coremod and mod loader classes.
     */
    @NotNull
    RfbSystemClassLoader compatClassLoader();

    /**
     * @return The {@link net.minecraft.launchwrapper.LaunchClassLoader} responsible for loading regular mod classes.
     */
    @NotNull
    ExtensibleClassLoader launchClassLoader();

    /**
     * Searches for a loaded plugin by ID. (Alternative IDs are searched too)
     * @param id The id or alternative ID to search for
     * @return Handle to the plugin and its associated metadata.
     */
    @Nullable
    RfbPluginHandle findPluginById(@NotNull String id);

    /**
     * @return An unmodifiable view of all the currently loaded RFB plugins.
     */
    @NotNull
    List<RfbPluginHandle> getLoadedPlugins();

    /**
     * @return The Java major version as an integer (e.g. 8, 9, 21)
     */
    int javaMajorVersion();

    /**
     * @return Java runtime version, for example 17.0.10 or 1.8.0.402-b06 (_ replaced with ., anything after + stripped)
     */
    @NotNull
    String javaVersion();

    /**
     * @return The game directory (parent of mods/, config/, saves/, etc.)
     */
    @NotNull
    Path gameDirectory();

    /**
     * @return The assets root directory
     */
    @NotNull
    Path assetsDirectory();
}
