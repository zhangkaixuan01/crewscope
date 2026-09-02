-- M8 review hardening: one RepositoryKey identifies one physical bare repository per deployment.
ALTER TABLE crewscope.github_repository_import_job
    DROP CONSTRAINT uk_github_import_target,
    ADD CONSTRAINT uk_github_import_repository_key UNIQUE (repository_key);
