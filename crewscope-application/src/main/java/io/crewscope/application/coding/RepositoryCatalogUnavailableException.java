package io.crewscope.application.coding;

/** Stable boundary raised when the current runtime profile cannot inspect managed repositories. */
public final class RepositoryCatalogUnavailableException extends RuntimeException {

    private static final String SAFE_MESSAGE =
            "Repository Catalog is unavailable on this server";

    public RepositoryCatalogUnavailableException() {
        super(SAFE_MESSAGE);
    }

    public RepositoryCatalogUnavailableException(Throwable cause) {
        super(SAFE_MESSAGE, cause);
    }
}
