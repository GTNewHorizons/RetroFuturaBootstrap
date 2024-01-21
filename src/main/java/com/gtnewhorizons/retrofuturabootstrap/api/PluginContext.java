package com.gtnewhorizons.retrofuturabootstrap.api;

import java.util.List;
import java.util.Map;

/**
 * Information passed from RFB to plugins during loading, encapsulated in a class to allow future additions.
 * The collections returned are mutable to avoid ugly reflection hacks when needed, but try to avoid mutating them unless absolutely necessary.
 */
public final class PluginContext {
    private final List<CompatibilityTransformerPluginMetadata> pluginMetadata;
    private final List<CompatibilityTransformerPlugin> loadedPlugins;
    private final Map<String, CompatibilityTransformerPluginMetadata> pluginMetadataById;
    private final Map<String, CompatibilityTransformerPlugin> loadedPluginsById;

    public PluginContext(
            List<CompatibilityTransformerPluginMetadata> pluginMetadata,
            List<CompatibilityTransformerPlugin> loadedPlugins,
            Map<String, CompatibilityTransformerPluginMetadata> pluginMetadataById,
            Map<String, CompatibilityTransformerPlugin> loadedPluginsById) {
        this.pluginMetadata = pluginMetadata;
        this.loadedPlugins = loadedPlugins;
        this.pluginMetadataById = pluginMetadataById;
        this.loadedPluginsById = loadedPluginsById;
    }

    /**
     * @return Metadata of all plugins that are and will be loaded, full list immediately available.
     */
    public List<CompatibilityTransformerPluginMetadata> pluginMetadata() {
        return pluginMetadata;
    }

    /**
     * @return Plugin classes of the plugins loaded so far during the loading process.
     */
    public List<CompatibilityTransformerPlugin> loadedPlugins() {
        return loadedPlugins;
    }

    /**
     * @return ID-indexed lookup table for plugin metadata.
     */
    public Map<String, CompatibilityTransformerPluginMetadata> pluginMetadataById() {
        return pluginMetadataById;
    }

    /**
     * @return ID-indexed lookup table for plugin classes.
     */
    public Map<String, CompatibilityTransformerPlugin> loadedPluginsById() {
        return loadedPluginsById;
    }
}
