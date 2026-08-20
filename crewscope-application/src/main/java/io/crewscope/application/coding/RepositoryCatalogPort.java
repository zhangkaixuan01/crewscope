package io.crewscope.application.coding;

import java.util.List;

/** Reads the path-free catalog maintained by the trusted Coding Worker. */
@FunctionalInterface
public interface RepositoryCatalogPort {

    List<RepositoryCatalogEntry> list();
}
