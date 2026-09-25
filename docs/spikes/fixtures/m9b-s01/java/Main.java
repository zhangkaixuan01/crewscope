/** Dependency-free compiler/runtime sample, not a product BuildProfile implementation. */
public final class Main {
    public static void main(String[] args) {
        if (Runtime.version().feature() != 17) {
            throw new AssertionError("Java 17 sample must run on Java 17");
        }
        if (2 + 3 != 5) {
            throw new AssertionError("Sample failed");
        }
        System.out.println("JAVA_SAMPLE_OK " + Runtime.version());
    }
}
