package com.gtnewhorizons.retrofuturabootstrap.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A runtime RFB plugin that can define a set of class transformers to transform any classes loaded through RFB.
 * Any jar that wants to register a RFB plugin during early load time must have a META-INF/rfb-plugin/my-plugin-id.properties file
 * declaring the plugin class and dependency information.
 * The runtime classpath is scanned, in addition to files with the .jar extension in the mods folder in the game directory.
 */
public interface CompatibilityTransformerPlugin {

    /**
     * @return Array of non-null transformers to register during the plugin construction phase, or null if none are needed. Useful for transforming other RFB plugins.
     */
    default @NotNull SimpleClassTransformer @Nullable [] getEarlyTransformers() {
        return null;
    }
}
