-- M10-I01a: pgvector extension for the optional knowledge embedding store.
-- This migration chain runs on its own Flyway history (flyway_vector_history) and only
-- when the operator opted in (crewscope.knowledge.vector.enabled=true) or an earlier
-- installation already carries the vector history. The extension itself lives in the
-- public schema so plain postgres:17 deployments simply never install it.
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
