package io.crewscope.application.retrieval;

/**
 * The six deployment-level knowledge switches (M13b-A03), read straight from the configuration
 * binding — never from the database. All six default off, so a deployment that has not opted in
 * reports a fully closed knowledge pipeline and the member-facing copy can say "the capability
 * is not enabled" instead of implying work in progress (first-round review R04).
 *
 * <p>The illegal combinations stay diagnosable: {@code index} without {@code vector} and
 * {@code retrieval} without {@code vector} are reported DOWN by the health indicators; this
 * board is the plain projection the product surface renders, not the guard.
 */
public record KnowledgeSwitchBoard(
        boolean vectorEnabled,
        boolean indexEnabled,
        boolean indexWorkerEnabled,
        boolean retrievalEnabled,
        boolean injectionEnabled,
        boolean skillEnabled) {
}
