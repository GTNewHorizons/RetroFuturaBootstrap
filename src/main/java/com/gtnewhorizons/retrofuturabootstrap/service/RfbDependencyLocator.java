package com.gtnewhorizons.retrofuturabootstrap.service;

import com.gtnewhorizons.retrofuturabootstrap.api.RfbPluginMetadata;
import java.util.List;
import org.jetbrains.annotations.NotNull;

public interface RfbDependencyLocator {
    @NotNull
    List<RfbPluginMetadata> locateDependencies(RfbPluginMetadata metadata);
}
