package com.gtnewhorizons.retrofuturabootstrap.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.jetbrains.annotations.NotNull;

/**
 * A handle to a {@link SimpleClassTransformer} with metadata about class exclusions and the associated plugin.
 */
public final class SimpleClassTransformerHandle {
    private final @NotNull String id;
    private final @NotNull CompatibilityTransformerPluginMetadata pluginMetadata;
    private final @NotNull CompatibilityTransformerPlugin plugin;
    private final @NotNull SimpleClassTransformer transformer;
    private final @NotNull List<@NotNull String> exclusions;

    /**
     * Creates the transformer handle and calculates the exclusion set for the given transformer.
     */
    public SimpleClassTransformerHandle(
            @NotNull CompatibilityTransformerPluginMetadata pluginMetadata,
            @NotNull CompatibilityTransformerPlugin plugin,
            @NotNull SimpleClassTransformer transformer) {
        this.pluginMetadata = pluginMetadata;
        this.plugin = plugin;
        this.transformer = transformer;
        final String xId = transformer.id();
        if (!CompatibilityTransformerPluginMetadata.ID_VALIDATOR.matcher(xId).matches()) {
            throw new RuntimeException("Illegal transfomer ID " + xId + " in RFB plugin " + pluginMetadata.id());
        }
        this.id = pluginMetadata().id() + ":" + transformer.id();
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
     * @return Metadata of the plugin that registered this transformer.
     */
    public @NotNull CompatibilityTransformerPluginMetadata pluginMetadata() {
        return pluginMetadata;
    }

    /**
     * @return Plugin that registered this transformer.
     */
    public @NotNull CompatibilityTransformerPlugin plugin() {
        return plugin;
    }

    /**
     * @return The transformer itself.
     */
    public @NotNull SimpleClassTransformer transformer() {
        return transformer;
    }

    /**
     * @return List of class name prefixes associated with this transformer.
     */
    public @NotNull List<@NotNull String> exclusions() {
        return exclusions;
    }
}
