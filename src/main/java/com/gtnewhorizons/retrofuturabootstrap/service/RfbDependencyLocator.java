package com.gtnewhorizons.retrofuturabootstrap.service;

import com.gtnewhorizons.retrofuturabootstrap.api.RfbPluginMetadata;
import java.util.List;
import org.jetbrains.annotations.NotNull;

public interface RfbDependencyLocator {
    /**
     * Locate dependencies for the given plugin metadata.
     *
     * @param metadata the metadata of the plugin to locate dependencies for
     * @return a list of metadata for the located dependencies
     */
    @NotNull
    List<RfbPluginMetadata> locateDependencies(RfbPluginMetadata metadata);
}
