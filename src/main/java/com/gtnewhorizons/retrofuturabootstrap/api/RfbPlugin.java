package com.gtnewhorizons.retrofuturabootstrap.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A runtime RFB plugin that can define a set of class transformers to transform any classes loaded through RFB.
 * Any jar that wants to register a RFB plugin during early load time must have a META-INF/rfb-plugin/my-plugin-id.properties file
 * declaring the plugin class and dependency information.
 * The runtime classpath is scanned, in addition to files with the .jar extension in the mods folder in the game directory.
 * The plugin class implementing this interface must have a public no-arguments constructor.
 */
public interface RfbPlugin {

    /**
     * Called immediately after constructing the plugin using a public no-argument constructor and adding it to the loaded plugins lists.
     */
    default void onConstruction(@NotNull PluginContext ctx) {}

    /**
     * These transformers are simply appended in-order to the transformer list during the plugin construction phase, with order following plugin load order.
     * Useful for transforming other RFB plugins, for other applications use the ordered transformer interface.
     * The transformers are simply inserted at the end of the transformer array during plugin loading, and only get re-sorted according to their sortAfter/sortBefore constraints after all plugins are constructed.
     *
     * @return Array of non-null transformers to register, or null if none are needed.
     */
    default @NotNull RfbClassTransformer @Nullable [] makeEarlyTransformers() {
        return null;
    }

    /**
     * Called after all plugins are constructed to gather RFB transformers to register in bulk.
     * Make sure to implement the sortAfter/sortBefore methods if you want to ensure a specific ordering of your transformer.
     *
     * @return Array of non-null transformers to register, or null if none are needed.
     */
    default @NotNull RfbClassTransformer @Nullable [] makeTransformers() {
        return null;
    }
}
