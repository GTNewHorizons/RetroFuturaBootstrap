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
        LCL_NO_TRANFORMS,
        /** The class would be loaded by LaunchClassLoader and transformed */
        LCL_WITH_TRANFORMS,
    }

    /**
     * @return A stable identifier for this transformer that can be used to declare dependencies between transformers, also used during class dumps for a part of a file name.
     */
    String name();

    /**
     * Called when this transformer is registered with a ClassLoader in RFB (this will happen twice, once for LaunchClassLoader and once for its parent loader).
     * @param classLoader The loader this transformer is being registered with now.
     */
    void onRegistration(ExtensibleClassLoader classLoader);

    /**
     * (Optionally) transform a given class.
     * @param classLoader The class loader asking for the transformation.
     * @param context The context in which the class is being loaded.
     * @param className The name of the transformed class.
     * @param classfileBuffer The input bytes of the classfile.
     * @return The optionally modified bytes of the classfile.
     */
    byte[] transformClass(ExtensibleClassLoader classLoader, Context context, String className, byte[] classfileBuffer);
}
