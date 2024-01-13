package com.gtnewhorizons.retrofuturabootstrap.api;

/**
 * A simple transformer that takes in class bytes and outputs different class bytes.
 * It should be thread-safe, and not change class names. It should also have a public no-arguments constructor.
 */
public interface SimpleClassTransformer {
    /** Context, in which the class is being loaded. */
    enum Context {
        /** The class would be loaded by the system ClassLoader in launchwrapper */
        SYSTEM,
        /** The class would be loaded by LaunchClassLoader, but is excluded from transformation */
        LCL_NO_TRANSFORMS,
        /** The class would be loaded by LaunchClassLoader and transformed */
        LCL_WITH_TRANSFORMS;
    }

    /**
     * @return A stable identifier for this transformer that can be used to declare dependencies between transformers, also used during class dumps for a part of a file name.
     */
    String name();

    /**
     * @return Array of "plugin:transformer" strings that this transformer should run after, include "*" to "pin" the transformer to the end of the list instead of the beginning.
     */
    default String[] sortAfter() {
        return null;
    }

    /**
     * @return Array of "plugin:transformer" strings that this transformer should run before.
     */
    default String[] sortBefore() {
        return null;
    }

    /**
     * Called when this transformer is registered with a ClassLoader in RFB (this will happen twice, once for LaunchClassLoader and once for its parent loader).
     * @param classLoader The loader this transformer is being registered with now.
     */
    default void onRegistration(ExtensibleClassLoader classLoader) {}

    /**
     * A fast scanning function that is used to determine if class transformations should be skipped altogether (if all transformers return false).
     * @param classLoader The class loader asking for the transformation.
     * @param context The context in which the class is being loaded.
     * @param className The name of the transformed class (in the dot-separated format).
     * @param classBytes The bytes of the class file to do lookups on, do not modify.
     * @return true if the class will be transformed by this class transformer.
     */
    boolean shouldTransformClass(
            ExtensibleClassLoader classLoader, Context context, String className, byte[] classBytes);

    /**
     * (Optionally) transform a given class. No ClassReader flags are used for maximum efficiency, so stack frames are not expanded.
     * @param classLoader The class loader asking for the transformation.
     * @param context The context in which the class is being loaded.
     * @param className The name of the transformed class (in the dot-separated format).
     * @param classNode The handle to the ASM-parsed class to modify, and metadata used for class writing.
     */
    void transformClass(
            ExtensibleClassLoader classLoader, Context context, String className, ClassNodeHandle classNode);
}
