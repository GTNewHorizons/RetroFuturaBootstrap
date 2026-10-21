package com.gtnewhorizons.retrofuturabootstrap.concurrency;

import java.util.ArrayList;

/** Stands in for a static list a transformer uses as its own recursion stack. */
public final class PerThreadList {

    private static final ThreadLocal<ArrayList<Object>> LISTS = ThreadLocal.withInitial(ArrayList::new);

    private PerThreadList() {}

    public static ArrayList<Object> get() {
        return LISTS.get();
    }
}
