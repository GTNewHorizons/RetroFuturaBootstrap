package launchwrapper;

/** A transformer that can modify/generate/destroy class file contents at class loading time. */
public interface IClassTransformer {
    /** Given a class untransformed and transformed names, and optionally exisiting class bytes, returns the new class bytes. Either or both of the input and output bytes can be null to add/remove a class. */
    byte[] transform(String name, String transformedName, byte[] basicClass);
}
