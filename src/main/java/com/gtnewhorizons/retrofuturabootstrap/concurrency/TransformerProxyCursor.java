package com.gtnewhorizons.retrofuturabootstrap.concurrency;

import java.util.IdentityHashMap;

/**
 * Per-thread replacement for CoreTweaks' {@code TransformerProxy.nextWrapper}. The proxy walks its wrappers with a
 * cursor stored in an instance field, so two threads transforming at the same time move each other's cursor.
 */
public final class TransformerProxyCursor {

    private static final ThreadLocal<IdentityHashMap<Object, int[]>> CURSORS =
            ThreadLocal.withInitial(IdentityHashMap::new);

    private TransformerProxyCursor() {}

    public static int get(Object proxy) {
        final int[] cursor = CURSORS.get().get(proxy);
        return cursor == null ? 0 : cursor[0];
    }

    public static void set(Object proxy, int value) {
        CURSORS.get().computeIfAbsent(proxy, k -> new int[1])[0] = value;
    }
}
