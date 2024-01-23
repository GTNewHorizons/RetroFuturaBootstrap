package com.gtnewhorizons.retrofuturabootstrap.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.jetbrains.annotations.NotNull;

/**
 * A handle to a {@link RfbClassTransformer} with metadata about class exclusions and the associated plugin.
 */
public final class RfbClassTransformerHandle {
    private final @NotNull String id;
    private final @NotNull String[] additionalIds;
    private final @NotNull RfbPluginMetadata pluginMetadata;
    private final @NotNull RfbPlugin plugin;
    private final @NotNull RfbClassTransformer transformer;
    private final @NotNull List<@NotNull String> exclusions;

    /**
     * Creates the transformer handle and calculates the exclusion set for the given transformer.
     */
    public RfbClassTransformerHandle(
            @NotNull RfbPluginMetadata pluginMetadata,
            @NotNull RfbPlugin plugin,
            @NotNull RfbClassTransformer transformer) {
        this.pluginMetadata = pluginMetadata;
        this.plugin = plugin;
        this.transformer = transformer;
        final String xId = transformer.id();
        if (!RfbPluginMetadata.ID_VALIDATOR.matcher(xId).matches()) {
            throw new RuntimeException("Illegal transfomer ID " + xId + " in RFB plugin " + pluginMetadata.id());
        }
        this.id = pluginMetadata().id() + ":" + transformer.id();
        final RfbPluginMetadata.IdAndVersion[] additionalPluginIds = pluginMetadata.additionalVersions();
        this.additionalIds = new String[additionalPluginIds.length];
        for (int i = 0; i < additionalPluginIds.length; i++) {
            this.additionalIds[i] = additionalPluginIds[i].id() + ":" + transformer.id();
        }
        // Deduplicate exclusions for faster lookup performance later
        final Set<String> allExclusions = new TreeSet<>();
        allExclusions.add(plugin.getClass().getPackage().getName() + ".");
        allExclusions.add(transformer.getClass().getPackage().getName() + ".");
        final String[] pluginExclusions = pluginMetadata.transformerExclusions();
        if (pluginExclusions != null) {
            allExclusions.addAll(Arrays.asList(pluginExclusions));
        }
        final String[] xformerExclusions = transformer.additionalExclusions();
        if (xformerExclusions != null) {
            allExclusions.addAll(Arrays.asList(xformerExclusions));
        }
        final ArrayList<@NotNull String> exclusions = new ArrayList<>(allExclusions.size());
        String previousExclusion = null;
        for (final String exclusion : allExclusions) {
            // Thanks to set ordering, "a.b." will come before "a.b.c." - we can filter out nested exclusions this way.
            if (previousExclusion != null && exclusion.startsWith(previousExclusion)) {
                continue;
            }
            previousExclusion = exclusion;
            exclusions.add(exclusion);
        }
        exclusions.trimToSize();
        this.exclusions = exclusions;
    }

    /**
     * @return The combined plugin:transformer identifier for this transformer.
     */
    public @NotNull String id() {
        return id;
    }

    /**
     * @return Additional altplugin:transformer identifiers, one for each alternative ID of the plugin.
     */
    public @NotNull String @NotNull [] additionalIds() {
        return additionalIds;
    }

    /**
     * @return Metadata of the plugin that registered this transformer.
     */
    public @NotNull RfbPluginMetadata pluginMetadata() {
        return pluginMetadata;
    }

    /**
     * @return Plugin that registered this transformer.
     */
    public @NotNull RfbPlugin plugin() {
        return plugin;
    }

    /**
     * @return The transformer itself.
     */
    public @NotNull RfbClassTransformer transformer() {
        return transformer;
    }

    /**
     * @return List of class name prefixes associated with this transformer.
     */
    public @NotNull List<@NotNull String> exclusions() {
        return exclusions;
    }
}
