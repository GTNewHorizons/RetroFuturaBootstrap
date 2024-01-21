package com.gtnewhorizons.retrofuturabootstrap.api;

import org.jetbrains.annotations.NotNull;

/**
 * A handle to a {@link RfbPlugin} with its associated metadata.
 */
public class RfbPluginHandle {
    private final @NotNull RfbPluginMetadata metadata;
    private final @NotNull RfbPlugin plugin;

    public RfbPluginHandle(@NotNull RfbPluginMetadata metadata, @NotNull RfbPlugin plugin) {
        this.metadata = metadata;
        this.plugin = plugin;
    }

    /**
     * @return Metadata of the loaded plugin.
     */
    public @NotNull RfbPluginMetadata metadata() {
        return metadata;
    }

    /**
     * @return The loaded plugin.
     */
    public @NotNull RfbPlugin plugin() {
        return plugin;
    }
}
