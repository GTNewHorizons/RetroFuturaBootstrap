package launchwrapper;

/** A transformer that can modify class names. */
public interface IClassNameTransformer {
    /** Maps from live to in-jar class names to fetch the original class files. */
    String unmapClassName(String name);

    /** Maps from live to "transformed" class names, passed to the class transformers and used as the loaded class name. */
    String remapClassName(String name);
}
