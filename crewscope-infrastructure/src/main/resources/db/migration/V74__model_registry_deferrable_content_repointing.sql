-- V74: make the content-addressed Model Registry foreign keys DEFERRABLE.
--
-- Platform-owned provider content converges to the current built-in definition across product
-- versions (DefaultPlatformModelCatalogInitializer). Adoption re-points the provider row and every
-- catalog entry, connection and agent binding still pinning the previous content hash inside ONE
-- transaction. The composite FKs below intentionally kept those re-points immediate, which made
-- any provider content change impossible in-place: updating the referenced row always violated a
-- still-pending referencing row before the transaction could reach a consistent state. The
-- constraints keep exactly their previous columns and semantics; only their check timing moves
-- from per-statement to commit (INITIALLY IMMEDIATE, deferrable per transaction via SET
-- CONSTRAINTS from the adopting adapter only).
ALTER TABLE crewscope.model_catalog_entry
    DROP CONSTRAINT fk_model_catalog_entry_provider,
    ADD CONSTRAINT fk_model_catalog_entry_provider
        FOREIGN KEY (provider_key, provider_definition_hash)
        REFERENCES crewscope.model_provider_definition (provider_key, content_hash)
        ON DELETE RESTRICT DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE crewscope.model_connection
    DROP CONSTRAINT fk_model_connection_provider,
    ADD CONSTRAINT fk_model_connection_provider
        FOREIGN KEY (provider_key, provider_definition_hash)
        REFERENCES crewscope.model_provider_definition (provider_key, content_hash)
        ON DELETE RESTRICT DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE crewscope.agent_configuration_model_binding
    DROP CONSTRAINT fk_agent_configuration_model_binding_primary_catalog,
    ADD CONSTRAINT fk_agent_configuration_model_binding_primary_catalog
        FOREIGN KEY (primary_catalog_entry_id, primary_provider_key, primary_model_id,
                     primary_catalog_revision, primary_provider_definition_hash,
                     primary_catalog_content_hash)
        REFERENCES crewscope.model_catalog_entry (
            id, provider_key, model_id, catalog_revision, provider_definition_hash, content_hash)
        ON DELETE RESTRICT DEFERRABLE INITIALLY IMMEDIATE,
    DROP CONSTRAINT fk_agent_configuration_model_binding_fallback_catalog,
    ADD CONSTRAINT fk_agent_configuration_model_binding_fallback_catalog
        FOREIGN KEY (fallback_catalog_entry_id, fallback_provider_key, fallback_model_id,
                     fallback_catalog_revision, fallback_provider_definition_hash,
                     fallback_catalog_content_hash)
        REFERENCES crewscope.model_catalog_entry (
            id, provider_key, model_id, catalog_revision, provider_definition_hash, content_hash)
        ON DELETE RESTRICT DEFERRABLE INITIALLY IMMEDIATE,
    DROP CONSTRAINT fk_agent_configuration_model_binding_primary_connection,
    ADD CONSTRAINT fk_agent_configuration_model_binding_primary_connection
        FOREIGN KEY (organization_id, primary_connection_id, primary_provider_key,
                     primary_provider_definition_hash)
        REFERENCES crewscope.model_connection (
            organization_id, id, provider_key, provider_definition_hash)
        ON DELETE RESTRICT DEFERRABLE INITIALLY IMMEDIATE,
    DROP CONSTRAINT fk_agent_configuration_model_binding_fallback_connection,
    ADD CONSTRAINT fk_agent_configuration_model_binding_fallback_connection
        FOREIGN KEY (organization_id, fallback_connection_id, fallback_provider_key,
                     fallback_provider_definition_hash)
        REFERENCES crewscope.model_connection (
            organization_id, id, provider_key, provider_definition_hash)
        ON DELETE RESTRICT DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE crewscope.agent_model_default
    DROP CONSTRAINT fk_agent_model_default_primary_catalog,
    ADD CONSTRAINT fk_agent_model_default_primary_catalog
        FOREIGN KEY (primary_catalog_entry_id, primary_provider_key, primary_model_id,
                     primary_catalog_revision, primary_provider_definition_hash,
                     primary_catalog_content_hash)
        REFERENCES crewscope.model_catalog_entry (
            id, provider_key, model_id, catalog_revision, provider_definition_hash, content_hash)
        ON DELETE RESTRICT DEFERRABLE INITIALLY IMMEDIATE,
    DROP CONSTRAINT fk_agent_model_default_fallback_catalog,
    ADD CONSTRAINT fk_agent_model_default_fallback_catalog
        FOREIGN KEY (fallback_catalog_entry_id, fallback_provider_key, fallback_model_id,
                     fallback_catalog_revision, fallback_provider_definition_hash,
                     fallback_catalog_content_hash)
        REFERENCES crewscope.model_catalog_entry (
            id, provider_key, model_id, catalog_revision, provider_definition_hash, content_hash)
        ON DELETE RESTRICT DEFERRABLE INITIALLY IMMEDIATE,
    DROP CONSTRAINT fk_agent_model_default_primary_connection,
    ADD CONSTRAINT fk_agent_model_default_primary_connection
        FOREIGN KEY (organization_id, primary_connection_id, primary_provider_key,
                     primary_provider_definition_hash)
        REFERENCES crewscope.model_connection (
            organization_id, id, provider_key, provider_definition_hash)
        ON DELETE RESTRICT DEFERRABLE INITIALLY IMMEDIATE,
    DROP CONSTRAINT fk_agent_model_default_fallback_connection,
    ADD CONSTRAINT fk_agent_model_default_fallback_connection
        FOREIGN KEY (organization_id, fallback_connection_id, fallback_provider_key,
                     fallback_provider_definition_hash)
        REFERENCES crewscope.model_connection (
            organization_id, id, provider_key, provider_definition_hash)
        ON DELETE RESTRICT DEFERRABLE INITIALLY IMMEDIATE;
