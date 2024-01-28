package com.gtnewhorizons.retrofuturabootstrap.api;

import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * Information passed from RFB to plugins during loading, encapsulated in a class to allow future additions.
 * The collections returned are mutable to avoid ugly reflection hacks when needed, but try to avoid mutating them unless absolutely necessary.
 */
public final class PluginContext {
    private final @NotNull List<@NotNull RfbPluginMetadata> pluginMetadata;
    private final @NotNull List<@NotNull RfbPluginHandle> loadedPlugins;
    private final @NotNull Map<@NotNull String, @NotNull RfbPluginMetadata> pluginMetadataById;
    private final @NotNull Map<@NotNull String, @NotNull RfbPluginHandle> loadedPluginsById;

    public PluginContext(
            @NotNull List<@NotNull RfbPluginMetadata> pluginMetadata,
            @NotNull List<@NotNull RfbPluginHandle> loadedPlugins,
            @NotNull Map<@NotNull String, @NotNull RfbPluginMetadata> pluginMetadataById,
            @NotNull Map<@NotNull String, @NotNull RfbPluginHandle> loadedPluginsById) {
        this.pluginMetadata = pluginMetadata;
        this.loadedPlugins = loadedPlugins;
        this.pluginMetadataById = pluginMetadataById;
        this.loadedPluginsById = loadedPluginsById;
    }

    /**
     * @return Metadata of all plugins that are and will be loaded, full list immediately available.
     */
    public @NotNull List<@NotNull RfbPluginMetadata> pluginMetadata() {
        return pluginMetadata;
    }

    /**
     * @return Plugin classes of the plugins loaded so far during the loading process.
     */
    public @NotNull List<@NotNull RfbPluginHandle> loadedPlugins() {
        return loadedPlugins;
    }

    /**
     * @return ID-indexed lookup table for plugin metadata.
     */
    public @NotNull Map<@NotNull String, @NotNull RfbPluginMetadata> pluginMetadataById() {
        return pluginMetadataById;
    }

    /**
     * @return ID-indexed lookup table for plugin classes.
     */
    public @NotNull Map<@NotNull String, @NotNull RfbPluginHandle> loadedPluginsById() {
        return loadedPluginsById;
    }
}
