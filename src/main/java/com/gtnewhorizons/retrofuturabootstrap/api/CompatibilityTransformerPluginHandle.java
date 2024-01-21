package com.gtnewhorizons.retrofuturabootstrap.api;

import org.jetbrains.annotations.NotNull;

/**
 * A handle to a {@link CompatibilityTransformerPlugin} with its associated metadata.
 */
public class CompatibilityTransformerPluginHandle {
    private final @NotNull CompatibilityTransformerPluginMetadata metadata;
    private final @NotNull CompatibilityTransformerPlugin plugin;

    public CompatibilityTransformerPluginHandle(
            @NotNull CompatibilityTransformerPluginMetadata metadata, @NotNull CompatibilityTransformerPlugin plugin) {
        this.metadata = metadata;
        this.plugin = plugin;
    }

    /**
     * @return Metadata of the loaded plugin.
     */
    public @NotNull CompatibilityTransformerPluginMetadata metadata() {
        return metadata;
    }

    /**
     * @return The loaded plugin.
     */
    public @NotNull CompatibilityTransformerPlugin plugin() {
        return plugin;
    }
}
