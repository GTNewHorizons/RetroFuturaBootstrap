package com.gtnewhorizons.retrofuturabootstrap.api;

import com.gtnewhorizons.retrofuturabootstrap.SimpleTransformingClassLoader;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The public Java 8-compatible RetroFuturaBootstrap API
 */
public interface RfbApi {
    /**
     * @return The Java 9+ Platform class loader, that has access to all the classes on the standard library module path. null on Java 8.
     */
    @Nullable
    ClassLoader platformClassLoader();

    /**
     * @return The System class loader, used for loading RFB itself and compatibility transformers.
     */
    @NotNull
    ClassLoader systemClassLoader();

    /**
     * @return The {@link com.gtnewhorizons.retrofuturabootstrap.SimpleTransformingClassLoader} responsible for loading coremod and mod loader classes.
     */
    @NotNull
    SimpleTransformingClassLoader compatClassLoader();

    /**
     * @return The {@link net.minecraft.launchwrapper.LaunchClassLoader} responsible for loading Minecraft and mod classes. null early in the loading process.
     */
    @Nullable
    LaunchClassLoader launchClassLoader();
}
