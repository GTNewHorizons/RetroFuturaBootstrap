package com.gtnewhorizons.retrofuturabootstrap.concurrency;

import com.gtnewhorizons.retrofuturabootstrap.api.ClassNodeHandle;
import com.gtnewhorizons.retrofuturabootstrap.api.ExtensibleClassLoader;
import com.gtnewhorizons.retrofuturabootstrap.api.RfbClassTransformer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Manifest;
import org.intellij.lang.annotations.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Makes class transformers that keep shared state safe to call from several threads at once, which they are as soon as
 * rfb.parallelClassLoading is on. Each entry mirrors a fix that belongs in the mod that owns the class.
 */
public class ConcurrentTransformersTransformer implements RfbClassTransformer, Opcodes {

    private static final String HOOKS = "com/gtnewhorizons/retrofuturabootstrap/concurrency/";
    private static final String COLLECTIONS = HOOKS + "ConcurrentCollections";
    private static final String FASTUTIL = "it/unimi/dsi/fastutil/objects/";
    private static final String MAP = "Ljava/util/Map;";
    private static final String SET = "Ljava/util/Set;";
    private static final String MAP_TO_MAP = "(Ljava/util/Map;)Ljava/util/Map;";
    private static final String SET_TO_SET = "(Ljava/util/Set;)Ljava/util/Set;";

    private static final Map<String, List<Patch>> PATCHES = new HashMap<>();
    /** Classes that got all their fixes, so the preloader can tell a target that loaded too early to be patched. */
    public static final Set<String> APPLIED = ConcurrentHashMap.newKeySet();
    /** Why classes cannot load in parallel after all, or null. */
    public static volatile String blockedReason;

    static {
        // CoreTweaks: the wrapper chain cursor becomes per thread
        final String proxy = "makamys/coretweaks/optimization/transformerproxy/TransformerProxy";
        add(proxy, new FieldAccess(proxy, "nextWrapper", HOOKS + "TransformerProxyCursor"));

        // FML: lazily built remapping caches
        final String remapper = "cpw/mods/fml/common/asm/transformers/deobf/FMLDeobfuscatingRemapper";
        for (String field : new String[] {"fieldNameMaps", "methodNameMaps", "fieldDescriptions"}) {
            add(remapper, new WrapWrite(remapper, field, MAP, COLLECTIONS, "concurrentMap", MAP_TO_MAP));
        }
        for (String field : new String[] {"negativeCacheMethods", "negativeCacheFields"}) {
            add(remapper, new WrapWrite(remapper, field, SET, COLLECTIONS, "concurrentSet", SET_TO_SET));
        }
        final String patcher = "cpw/mods/fml/common/patcher/ClassPatchManager";
        add(patcher, new WrapWrite(patcher, "patchedClasses", MAP, COLLECTIONS, "synchronizedMap", MAP_TO_MAP));

        // Mixin: class metadata is also looked up by class writers outside the Mixin transformer lock
        add(
                "org/spongepowered/asm/mixin/transformer/ClassInfo",
                new Synchronize("forName", "fromClassNode", "fromCache"));

        // GTNHLib: @EventBusSubscriber data collected while transforming and read while registering
        final String eventBus = "com/gtnewhorizon/gtnhlib/eventbus/EventBusUtil";
        final String set = "L" + FASTUTIL + "ObjectSet;";
        final String map = "L" + FASTUTIL + "Object2ObjectMap;";
        final String list = "L" + FASTUTIL + "ObjectList;";
        add(eventBus, fastutil(eventBus, "classesToVisit", set, "ObjectSets"));
        add(eventBus, fastutil(eventBus, "methodsToSubscribe", map, "Object2ObjectMaps"));
        add(eventBus, fastutil(eventBus, "conditionsToCheck", map, "Object2ObjectMaps"));
        add(eventBus, fastutil(eventBus, "invalidMethods", list, "ObjectLists"));
        // GTNHLib: Block subclass tracking, every class passes through it
        add("com/gtnewhorizon/gtnhlib/core/fml/transformers/BlockIconTransformer", new Synchronize("isBlockSubclass"));

        // CoFH: a recursion guard shared by all threads, lazily filled mod maps, data published through a flag
        final String cofh = "cofh/asm/ASMCore";
        add(cofh, new StaticRead(cofh, "workingPath", HOOKS + "PerThreadList", "get", "()Ljava/util/ArrayList;"));
        add(cofh, new Synchronize("getLoadedMods", "getLoadedAPIs"));
        add("cofh/asm/CoFHClassTransformer", new MakeVolatile("scrappedData"));

        // OpenComputers: copies methods out of a shared template node, which ASM mutates while visiting it
        add("li/cil/oc/common/asm/ASMHelpers", new Synchronize("copyMethodNode"));

        // AE2: integration modules are set up lazily from inside its transformer until AE2's own preInit
        add("appeng/integration/IntegrationNode", new Synchronize("isActive"));

        // OpenMods: generated class providers, registered during mod init while classes load
        final String injected = "openmods/injector/InjectedClassesManager";
        add(injected, new WrapWrite(injected, "providers", MAP, COLLECTIONS, "concurrentMap", MAP_TO_MAP));
    }

    private static Patch fastutil(String owner, String field, String desc, String wrappers) {
        return new WrapWrite(owner, field, desc, FASTUTIL + wrappers, "synchronize", "(" + desc + ")" + desc);
    }

    private static void add(String internalName, Patch patch) {
        PATCHES.computeIfAbsent(internalName.replace('/', '.'), k -> new ArrayList<>())
                .add(patch);
    }

    @Pattern("[a-z0-9-]+")
    @Override
    public @NotNull String id() {
        return "concurrent-transformers";
    }

    @Override
    public boolean shouldTransformClass(
            @NotNull ExtensibleClassLoader classLoader,
            @NotNull RfbClassTransformer.Context context,
            @Nullable Manifest manifest,
            @NotNull String className,
            @NotNull ClassNodeHandle classNode) {
        return classNode.isPresent() && PATCHES.containsKey(className);
    }

    @Override
    public void transformClass(
            @NotNull ExtensibleClassLoader classLoader,
            @NotNull RfbClassTransformer.Context context,
            @Nullable Manifest manifest,
            @NotNull String className,
            @NotNull ClassNodeHandle classNode) {
        final ClassNode cn = classNode.getNode();
        if (cn == null) return;
        for (Patch patch : PATCHES.get(className)) {
            if (patch.fixedUpstream(cn)) continue;
            if (patch.apply(cn) == 0) {
                // the target changed shape, so it stays unsafe and the loader has to stay serial
                blockedReason = "could not apply " + patch + " to " + className;
                return;
            }
        }
        APPLIED.add(className);
    }

    public static Set<String> targets() {
        return Collections.unmodifiableSet(PATCHES.keySet());
    }

    private interface Patch {

        /** @return number of instructions changed */
        int apply(ClassNode cn);

        /** True when the mod already ships this fix, so there is nothing to patch. */
        default boolean fixedUpstream(ClassNode cn) {
            return false;
        }
    }

    /** Passes every value stored into the field through a static hook first. */
    private static final class WrapWrite implements Patch {

        private final String owner, field, fieldDesc, hookOwner, hookName, hookDesc;

        WrapWrite(String owner, String field, String fieldDesc, String hookOwner, String hookName, String hookDesc) {
            this.owner = owner;
            this.field = field;
            this.fieldDesc = fieldDesc;
            this.hookOwner = hookOwner;
            this.hookName = hookName;
            this.hookDesc = hookDesc;
        }

        @Override
        public String toString() {
            return "wrap of " + field;
        }

        @Override
        public int apply(ClassNode cn) {
            int changed = 0;
            for (MethodNode mn : cn.methods) {
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if ((insn.getOpcode() == PUTFIELD || insn.getOpcode() == PUTSTATIC)
                            && insn instanceof FieldInsnNode
                            && ((FieldInsnNode) insn).owner.equals(owner)
                            && ((FieldInsnNode) insn).name.equals(field)) {
                        final FieldInsnNode fin = (FieldInsnNode) insn;
                        // a field declared as a concrete class would not accept the replacement
                        if (!fin.desc.equals(fieldDesc)) return 0;
                        mn.instructions.insertBefore(
                                insn, new MethodInsnNode(INVOKESTATIC, hookOwner, hookName, hookDesc, false));
                        changed++;
                    }
                }
            }
            return changed;
        }
    }

    /** Turns reads and writes of an int field into calls to {@code get(Object)} and {@code set(Object, int)}. */
    private static final class FieldAccess implements Patch {

        private final String owner, field, hookOwner;

        FieldAccess(String owner, String field, String hookOwner) {
            this.owner = owner;
            this.field = field;
            this.hookOwner = hookOwner;
        }

        @Override
        public String toString() {
            return "per thread " + field;
        }

        @Override
        public boolean fixedUpstream(ClassNode cn) {
            for (FieldNode fn : cn.fields) {
                if (fn.name.equals(field)) return !fn.desc.equals("I");
            }
            return false;
        }

        @Override
        public int apply(ClassNode cn) {
            int changed = 0;
            for (MethodNode mn : cn.methods) {
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (!(insn instanceof FieldInsnNode)) continue;
                    final FieldInsnNode fin = (FieldInsnNode) insn;
                    if (!fin.owner.equals(owner) || !fin.name.equals(field) || !fin.desc.equals("I")) continue;
                    if (insn.getOpcode() == GETFIELD) {
                        mn.instructions.set(
                                insn,
                                new MethodInsnNode(INVOKESTATIC, hookOwner, "get", "(Ljava/lang/Object;)I", false));
                        changed++;
                    } else if (insn.getOpcode() == PUTFIELD) {
                        mn.instructions.set(
                                insn,
                                new MethodInsnNode(INVOKESTATIC, hookOwner, "set", "(Ljava/lang/Object;I)V", false));
                        changed++;
                    }
                }
            }
            return changed;
        }
    }

    /** Marks methods synchronized. Only for methods that never load a class while running. */
    private static final class Synchronize implements Patch {

        private final String[] names;

        Synchronize(String... names) {
            this.names = names;
        }

        @Override
        public String toString() {
            return "synchronized " + String.join(", ", names);
        }

        @Override
        public int apply(ClassNode cn) {
            int changed = 0;
            for (MethodNode mn : cn.methods) {
                for (String name : names) {
                    if (mn.name.equals(name)) {
                        mn.access |= ACC_SYNCHRONIZED;
                        changed++;
                    }
                }
            }
            return changed;
        }
    }

    private static final class MakeVolatile implements Patch {

        private final String field;

        MakeVolatile(String field) {
            this.field = field;
        }

        @Override
        public String toString() {
            return "volatile " + field;
        }

        @Override
        public int apply(ClassNode cn) {
            for (FieldNode fn : cn.fields) {
                if (fn.name.equals(field)) {
                    fn.access |= ACC_VOLATILE;
                    return 1;
                }
            }
            return 0;
        }
    }

    /** Reads of a static field go through a hook instead, for state that has to become per thread. */
    private static final class StaticRead implements Patch {

        private final String owner, field, hookOwner, hookName, hookDesc;

        StaticRead(String owner, String field, String hookOwner, String hookName, String hookDesc) {
            this.owner = owner;
            this.field = field;
            this.hookOwner = hookOwner;
            this.hookName = hookName;
            this.hookDesc = hookDesc;
        }

        @Override
        public String toString() {
            return "per thread " + field;
        }

        @Override
        public int apply(ClassNode cn) {
            int changed = 0;
            for (MethodNode mn : cn.methods) {
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (insn.getOpcode() == GETSTATIC
                            && insn instanceof FieldInsnNode
                            && ((FieldInsnNode) insn).owner.equals(owner)
                            && ((FieldInsnNode) insn).name.equals(field)) {
                        mn.instructions.set(
                                insn, new MethodInsnNode(INVOKESTATIC, hookOwner, hookName, hookDesc, false));
                        changed++;
                    }
                }
            }
            return changed;
        }
    }
}
