package com.gtnewhorizons.retrofuturabootstrap.plugin;

import com.gtnewhorizons.retrofuturabootstrap.Main;
import com.gtnewhorizons.retrofuturabootstrap.algorithm.StableTopologicalSort;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbPluginMetadata;
import com.gtnewhorizons.retrofuturabootstrap.versioning.ArtifactVersion;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.VisibleForTesting;

public class PluginSorter {
    protected final List<RfbPluginMetadata> plugins = new ArrayList<>();
    protected final Set<RfbPluginMetadata> duplicateDisables = Collections.newSetFromMap(new IdentityHashMap<>());
    protected boolean criticalIssuesFound = false;

    public PluginSorter(List<RfbPluginMetadata> metadata) {
        this.plugins.addAll(metadata);
        this.plugins.sort(RfbPluginMetadata.ID_AND_PIN_COMPARATOR);
    }

    /**
     * @return The still-enabled plugin metadata in load order, or empty option if there were unresolved conflicts.
     */
    public Optional<List<RfbPluginMetadata>> resolve() {
        handleDuplicates();
        handleLoadRelations();
        return criticalIssuesFound ? Optional.empty() : Optional.of(plugins);
    }

    /**
     * Disables any plugins that have an equal ID with a newer version present.
     */
    @VisibleForTesting
    public void handleDuplicates() {
        final Map<String, List<RfbPluginMetadata>> idLookup = new HashMap<>(plugins.size());
        // build lookup map
        for (RfbPluginMetadata plugin : plugins) {
            idLookup.computeIfAbsent(plugin.id(), _id -> new ArrayList<>(1)).add(plugin);
            for (RfbPluginMetadata.IdAndVersion additionalId : plugin.additionalVersions()) {
                idLookup.computeIfAbsent(additionalId.id(), _id -> new ArrayList<>(2))
                        .add(plugin);
            }
        }
        // find and disable duplicates
        for (Map.Entry<String, List<RfbPluginMetadata>> entry : idLookup.entrySet()) {
            final String id = entry.getKey();
            final List<RfbPluginMetadata> equalIdPlugins = entry.getValue();
            if (equalIdPlugins.size() < 2) {
                continue;
            }
            RfbPluginMetadata newest = null;
            ArtifactVersion newestVersion = null;
            for (final RfbPluginMetadata it : equalIdPlugins) {
                if (duplicateDisables.contains(it)) {
                    continue;
                }
                if (newest == null) {
                    newest = it;
                    newestVersion = Objects.requireNonNull(it.version(id));
                    continue;
                }
                final ArtifactVersion itVersion = Objects.requireNonNull(it.version(id));
                if (itVersion.compareTo(newestVersion) > 0) {
                    duplicateDisables.add(newest);
                    Main.logger.warn(
                            "Duplicate RFB plugin ID `{}` found, disabling `{}@{}` ({}) in favor of `{}@{}` ({})",
                            id,
                            newest.id(),
                            newestVersion,
                            newest.source(),
                            it.id(),
                            itVersion,
                            it.source());
                    newest = it;
                    newestVersion = itVersion;
                } else {
                    duplicateDisables.add(it);
                    Main.logger.warn(
                            "Duplicate RFB plugin ID `{}` found, disabling `{}@{}` ({}) in favor of `{}@{}` ({})",
                            id,
                            it.id(),
                            itVersion,
                            it.source(),
                            newest.id(),
                            newestVersion,
                            newest.source());
                }
            }
        }
        plugins.removeIf(duplicateDisables::contains);
    }

    /**
     * Sort the plugins in order of loading relations (before/after), and detects any critical issues (conflicts, version requirement mismatches).
     * Requires the plugins to be deduplicated first.
     */
    @VisibleForTesting
    public void handleLoadRelations() {
        final Map<String, RfbPluginMetadata> pluginsById = new HashMap<>(plugins.size());
        final Map<String, ArtifactVersion> versionsById = new HashMap<>(plugins.size());
        final Map<RfbPluginMetadata, Integer> pluginToIndex = new IdentityHashMap<>(plugins.size());
        // build lookup maps
        for (int index = 0; index < plugins.size(); index++) {
            final RfbPluginMetadata plugin = plugins.get(index);
            pluginToIndex.put(plugin, index);
            if (pluginsById.put(plugin.id(), plugin) != null) {
                throw new IllegalStateException("Plugins not deduplicated: " + plugin.id());
            }
            versionsById.put(plugin.id(), plugin.version());
            for (RfbPluginMetadata.IdAndVersion additionalId : plugin.additionalVersions()) {
                if (pluginsById.put(additionalId.id(), plugin) != null) {
                    throw new IllegalStateException("Plugins not deduplicated: " + additionalId.id());
                }
                versionsById.put(additionalId.id(), additionalId.version());
            }
        }
        // check conflicts and requirements
        for (RfbPluginMetadata plugin : plugins) {
            for (final RfbPluginMetadata.IdAndVersionRange constraint : plugin.versionConstraints()) {
                final String constraintId = constraint.id();
                final ArtifactVersion constraintLoadedVersion = versionsById.get(constraintId);
                if (constraintLoadedVersion != null) {
                    if (!constraint.version().containsVersion(constraintLoadedVersion)) {
                        final RfbPluginMetadata constraintLoaded = pluginsById.get(constraintId);
                        Main.logger.error(
                                "Version requirement not satisfied: `{}` ({}) requires `{}`, but version `{}` ({}) was found",
                                plugin.idAndVersion(),
                                plugin.source(),
                                constraint.version(),
                                constraintLoaded.idAndVersion(),
                                constraintLoaded.source());
                        criticalIssuesFound = true;
                    }
                }
            }
            for (final String required : plugin.loadRequires()) {
                final RfbPluginMetadata loaded = pluginsById.get(required);
                if (loaded == null) {
                    String verReq = "any version";
                    for (RfbPluginMetadata.IdAndVersionRange r : plugin.versionConstraints()) {
                        if (r.id().equals(required)) {
                            verReq = r.version().toString();
                        }
                    }
                    Main.logger.error(
                            "Requirement not met: `{}` ({}) requires `{}@{}`, but it was not found among enabled plugins.",
                            plugin.idAndVersion(),
                            plugin.source(),
                            required,
                            verReq);
                    criticalIssuesFound = true;
                }
            }
        }
        if (criticalIssuesFound) {
            return;
        }
        // Sort
        final List<List<Integer>> beforeEdges = new ArrayList<>(plugins.size());

        for (int i = 0; i < plugins.size(); i++) {
            beforeEdges.add(new ArrayList<>());
        }
        for (final RfbPluginMetadata plugin : plugins) {
            final int myIndex = pluginToIndex.get(plugin);
            // add "before" dependencies
            for (final String otherId : plugin.loadBefore()) {
                final RfbPluginMetadata other = pluginsById.get(otherId);
                if (other != null) {
                    final int otherIndex = pluginToIndex.get(other);
                    beforeEdges.get(myIndex).add(otherIndex);
                }
            }
            // add "after" dependencies
            for (final String otherId : plugin.loadAfter()) {
                final RfbPluginMetadata other = pluginsById.get(otherId);
                if (other != null) {
                    final int otherIndex = pluginToIndex.get(other);
                    beforeEdges.get(otherIndex).add(myIndex);
                }
            }
        }

        try {
            final List<RfbPluginMetadata> sorted = StableTopologicalSort.sort(plugins, beforeEdges);
            this.plugins.clear();
            this.plugins.addAll(sorted);
        } catch (StableTopologicalSort.CycleException err) {
            final Set<RfbPluginMetadata> cycle = err.cyclicElements(RfbPluginMetadata.class);
            Main.logger.error("Cycle found among the following RFB plugins, aborting launch:");
            for (final RfbPluginMetadata plugin : cycle) {
                Main.logger.error("{} ({})", plugin.idAndVersion(), plugin.source());
            }
            criticalIssuesFound = true;
        }
    }
}
