package com.gtnewhorizons.retrofuturabootstrap.concurrency;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Swapped in for plain collections that class transformers fill lazily while classes load. */
public final class ConcurrentCollections {

    private ConcurrentCollections() {}

    public static Map<Object, Object> concurrentMap(Map<Object, Object> map) {
        return new ConcurrentHashMap<>(map);
    }

    public static Set<Object> concurrentSet(Set<Object> set) {
        final Set<Object> result = ConcurrentHashMap.newKeySet(Math.max(16, set.size()));
        result.addAll(set);
        return result;
    }

    // for maps that may hold null values
    public static Map<Object, Object> synchronizedMap(Map<Object, Object> map) {
        return Collections.synchronizedMap(map);
    }
}
