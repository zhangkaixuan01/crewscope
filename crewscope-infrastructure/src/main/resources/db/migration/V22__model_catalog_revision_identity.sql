-- ModelCatalogEntry keeps one stable Entry ID while immutable catalog revisions advance. V20
-- accidentally made the stable ID globally unique, which prevented every revision after one.
ALTER TABLE crewscope.model_catalog_entry
    DROP CONSTRAINT model_catalog_entry_pkey,
    ADD CONSTRAINT model_catalog_entry_pkey PRIMARY KEY (id, catalog_revision),
    ADD CONSTRAINT fk_model_catalog_entry_previous_revision
        FOREIGN KEY (id, provider_key, model_id, previous_catalog_revision)
        REFERENCES crewscope.model_catalog_entry (
            id, provider_key, model_id, catalog_revision
        ) ON DELETE RESTRICT;

-- Price revisions restart at one for each exact catalog revision, so the Catalog Revision is also
-- part of the price stream identity and effective-time uniqueness boundary.
ALTER TABLE crewscope.model_price_revision
    DROP CONSTRAINT model_price_revision_pkey,
    DROP CONSTRAINT uk_model_price_revision_effective,
    ADD CONSTRAINT model_price_revision_pkey
        PRIMARY KEY (catalog_entry_id, catalog_revision, price_revision),
    ADD CONSTRAINT uk_model_price_revision_effective
        UNIQUE (catalog_entry_id, catalog_revision, effective_from);

DROP INDEX crewscope.ix_model_price_revision_effective_lookup;

CREATE INDEX ix_model_price_revision_effective_lookup
    ON crewscope.model_price_revision (
        catalog_entry_id, catalog_revision, effective_from DESC, price_revision DESC
    );
