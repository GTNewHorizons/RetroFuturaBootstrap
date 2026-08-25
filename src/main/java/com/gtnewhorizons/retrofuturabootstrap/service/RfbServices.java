package com.gtnewhorizons.retrofuturabootstrap.service;

import com.gtnewhorizons.retrofuturabootstrap.Main;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

public class RfbServices {
    private static List<RfbDependencyLocator> dependencyLocators;

    public static List<RfbDependencyLocator> getDependencyLocators() {
        if (dependencyLocators == null) {
            dependencyLocators = new ArrayList<>();
            final ServiceLoader<RfbDependencyLocator> serviceLoader = ServiceLoader.load(RfbDependencyLocator.class);
            for (RfbDependencyLocator locator : serviceLoader) {
                Main.logger.info(
                        "Found dependency locator: {}", locator.getClass().getName());
                dependencyLocators.add(locator);
            }
        }
        return dependencyLocators;
    }
}
