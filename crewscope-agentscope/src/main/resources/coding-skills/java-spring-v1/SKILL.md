---
name: java-spring-v1
description: CrewScope's version-pinned Java and Spring Boot repository implementation rules.
---

# Java and Spring Boot Repository Skill v1

## Repository analysis

1. Read the root build descriptor and the target production files before proposing a change.
2. Locate tests and neighboring conventions with bounded repository search.
3. Preserve Java 17 compatibility, package boundaries and constructor injection.

## Implementation

1. Prefer a small domain-level fix over broad rewrites.
2. Add comments for security boundaries, non-obvious invariants and recovery decisions.
3. Keep HTTP, application and infrastructure concerns in their existing layers.
4. Use `Locale.ROOT`, explicit time sources and deterministic ordering where applicable.
5. Preserve validation, authorization, tenant scope, idempotency and secret handling.

## Verification

1. Run only acceptance commands published by the platform.
2. Treat exit code, timeout and platform TestEvidence as authoritative.
3. Inspect the final Git diff and confirm every changed path is allowed.
4. Report remaining risks without converting them into successful evidence.
