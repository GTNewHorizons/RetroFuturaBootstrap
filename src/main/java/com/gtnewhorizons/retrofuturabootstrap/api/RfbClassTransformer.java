package com.gtnewhorizons.retrofuturabootstrap.api;

import java.util.jar.Manifest;
import org.intellij.lang.annotations.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A simple transformer that takes in class bytes and outputs different class bytes.
 * It should be thread-safe, and not change class names. It should also have a public no-arguments constructor.
 */
public interface RfbClassTransformer {
    /** Context, in which the class is being loaded. */
    enum Context {
        /** The class would be loaded by the system ClassLoader in launchwrapper */
        SYSTEM,
        /** The class would be loaded by LaunchClassLoader, but is excluded from transformation */
        LCL_NO_TRANSFORMS,
        /** The class would be loaded by LaunchClassLoader and transformed */
        LCL_WITH_TRANSFORMS
    }

    /**
     * @return A stable identifier for this transformer that can be used to declare dependencies between transformers, also used during class dumps for a part of a file name. Use only [a-z0-9-] characters for consistency.
     */
    @NotNull
    @Pattern("[a-z0-9-]+")
    String id();

    /**
     * @return Array of "plugin:transformer" strings that this transformer should run after, include "*" to "pin" the transformer to the end of the list instead of the beginning. Return null if none.
     */
    default @NotNull String @Nullable [] sortAfter() {
        return null;
    }

    /**
     * @return Array of "plugin:transformer" strings that this transformer should run before. Return null if none.
     */
    default @NotNull String @Nullable [] sortBefore() {
        return null;
    }

    /**
     * @return Array of class name prefixes (in the dot-separated format) to exclude from transformation by this transformer on top of the plugin's exclusions. Return null if none.
     */
    default @NotNull String @Nullable [] additionalExclusions() {
        return null;
    }

    /**
     * Called when this transformer is registered with a ClassLoader in RFB (this will happen twice, once for LaunchClassLoader and once for its parent loader).
     * @param classLoader The loader this transformer is being registered with now.
     */
    default void onRegistration(@NotNull ExtensibleClassLoader classLoader) {}

    /**
     * A fast scanning function that is used to determine if class transformations should be skipped altogether (if all transformers return false).
     * @param classLoader The class loader asking for the transformation.
     * @param context The context in which the class is being loaded.
     * @param manifest Manifest of the JAR from which the package of this class came, or null if not present.
     * @param className The name of the transformed class (in the dot-separated format).
     * @param classNode The handle to the class data and parsed metadata, try to avoid triggering the lazy ASM parse if possible for performance.
     * @return true if the class will be transformed by this class transformer.
     */
    boolean shouldTransformClass(
            @NotNull ExtensibleClassLoader classLoader,
            @NotNull Context context,
            @Nullable Manifest manifest,
            @NotNull String className,
            @NotNull ClassNodeHandle classNode);

    /**
     * (Optionally) transform a given class. No ClassReader flags are used for maximum efficiency, so stack frames are not expanded.
     * @param classLoader The class loader asking for the transformation.
     * @param context The context in which the class is being loaded.
     * @param manifest Manifest of the JAR from which the package of this class came, or null if not present.
     * @param className The name of the transformed class (in the dot-separated format).
     * @param classNode The handle to the lazily ASM-parsed class to modify, and metadata used for class writing.
     */
    void transformClass(
            @NotNull ExtensibleClassLoader classLoader,
            @NotNull Context context,
            @Nullable Manifest manifest,
            @NotNull String className,
            @NotNull ClassNodeHandle classNode);
}
