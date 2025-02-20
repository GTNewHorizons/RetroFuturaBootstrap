package com.gtnewhorizons.retrofuturabootstrap.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A handle to a {@link RfbPlugin} with its associated metadata.
 */
public class RfbPluginHandle {
    private final @NotNull RfbPluginMetadata metadata;
    private final @NotNull RfbPlugin plugin;
    private final @NotNull List<RfbClassTransformerHandle> transformers;
    private final @NotNull List<RfbClassTransformerHandle> transformersView;
    private final @NotNull Map<String, RfbClassTransformerHandle> transformersNameMap;

    public RfbPluginHandle(@NotNull RfbPluginMetadata metadata, @NotNull RfbPlugin plugin) {
        this.metadata = metadata;
        this.plugin = plugin;
        this.transformers = new ArrayList<>(4);
        this.transformersView = Collections.unmodifiableList(transformers);
        this.transformersNameMap = new HashMap<>(4);
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

    /**
     * @return The list of currently constructed transformers that were registered by this plugin. Cannot be mutated.
     */
    public @NotNull List<RfbClassTransformerHandle> transformers() {
        return transformersView;
    }

    /**
     * @param id The transformer ID to look for, without the plugin ID (e.g. {@code "redirect"} if looking for {@code "lwjgl3ify:redirect"})
     * @return The transformer that matches the given ID, or {@code null} if not found.
     */
    public @Nullable RfbClassTransformerHandle findTransformerById(@NotNull String id) {
        return transformersNameMap.get(id);
    }

    /**
     * Mostly for internal use, used to register a newly constructed transformer with the plugin that requested it.
     * @param handle The handle of the transformer to register
     */
    public void registerAdditionalTransformer(@NotNull RfbClassTransformerHandle handle) {
        Objects.requireNonNull(handle);
        if (handle.plugin() != plugin) {
            throw new IllegalArgumentException(
                    "Trying to add a transformer " + handle.id() + " to mismatched plugin " + metadata.id());
        }
        final RfbClassTransformer xformer = handle.transformer();
        if (transformersNameMap.putIfAbsent(xformer.id(), handle) != null) {
            throw new IllegalArgumentException(
                    "Trying to register a transformer with a duplicate name: " + handle.id());
        }
        transformers.add(handle);
    }
}
