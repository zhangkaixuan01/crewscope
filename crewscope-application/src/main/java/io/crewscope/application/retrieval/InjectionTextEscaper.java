package io.crewscope.application.retrieval;

/**
 * The single escaping table shared by the budget estimator and the prompt renderer
 * (M10-I02b): retrieved titles, contents and fragment paths are untrusted data. The
 * budget must price the text exactly as it will be rendered — escaped — or an
 * ampersand-heavy payload would slip past the layer budgets (each {@code &} grows to
 * {@code &amp;}) and later break the instruction character bound. Sharing one
 * implementation keeps the estimator and the renderer from drifting apart.
 */
public final class InjectionTextEscaper {

    private InjectionTextEscaper() {
    }

    public static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
