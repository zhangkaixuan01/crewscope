package io.crewscope.application.search;

/** Read port for the bounded, member-authorized search projection. */
public interface SearchIndexPort { SearchResultPage search(SearchQuery query); }
