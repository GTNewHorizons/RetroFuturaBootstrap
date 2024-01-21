package com.gtnewhorizons.retrofuturabootstrap.api;

import java.util.List;
import java.util.Map;

/**
 * Information passed from RFB to plugins during loading, encapsulated in a class to allow future additions.
 * The collections returned are mutable to avoid ugly reflection hacks when needed, but try to avoid mutating them unless absolutely necessary.
 */
public final class PluginContext {
    private final List<RfbPluginMetadata> pluginMetadata;
    private final List<RfbPluginHandle> loadedPlugins;
    private final Map<String, RfbPluginMetadata> pluginMetadataById;
    private final Map<String, RfbPluginHandle> loadedPluginsById;

    public PluginContext(
            List<RfbPluginMetadata> pluginMetadata,
            List<RfbPluginHandle> loadedPlugins,
            Map<String, RfbPluginMetadata> pluginMetadataById,
            Map<String, RfbPluginHandle> loadedPluginsById) {
        this.pluginMetadata = pluginMetadata;
        this.loadedPlugins = loadedPlugins;
        this.pluginMetadataById = pluginMetadataById;
        this.loadedPluginsById = loadedPluginsById;
    }

    /**
     * @return Metadata of all plugins that are and will be loaded, full list immediately available.
     */
    public List<RfbPluginMetadata> pluginMetadata() {
        return pluginMetadata;
    }

    /**
     * @return Plugin classes of the plugins loaded so far during the loading process.
     */
    public List<RfbPluginHandle> loadedPlugins() {
        return loadedPlugins;
    }

    /**
     * @return ID-indexed lookup table for plugin metadata.
     */
    public Map<String, RfbPluginMetadata> pluginMetadataById() {
        return pluginMetadataById;
    }

    /**
     * @return ID-indexed lookup table for plugin classes.
     */
    public Map<String, RfbPluginHandle> loadedPluginsById() {
        return loadedPluginsById;
    }
}
