---
name: testing-pyramid
description: Use when writing or reviewing Spring Boot 3 public-module behavior tests, HTTP/security contracts, configuration binding or isolated MySQL/Redis integration checks.
---

# Risk-driven Spring Boot 3 testing

Read [the project contract](../PROJECT.md) and the affected module's current tests. Preserve JUnit/AssertJ/Mockito conventions; choose test layers by the failure they can detect, not by a fixed ratio.

## Public-interface behavior

Use deterministic clocks and synthetic identities/data. Assert a consumer-visible boundary, invariant, transition, precedence, failure or committed result. Avoid source-text, exact incidental wording, mock echoes, copied wiring, bare not-throw and repeated same-path cases.

Tests import package-root module entry points, not internal Controllers or implementation classes. Reuse existing public fixture/application factories. Do not broaden the production interface only to make a particular internal method testable.

## HTTP and security

- Use standalone MockMvc/public factories when they prove the changed HTTP contract; retain full context for behavior that depends on actual filter/bean assembly.
- A WebMvcTest example is not permission to import an internal class across the module seam or bypass the real security chain.
- Prefer @MockitoBean/@MockitoSpyBean for new Boot 3.5 context overrides; do not mass-rename existing tests as incidental cleanup.
- @WithMockUser alone does not prove this application's upstream identity verification, admin Cookie, CSRF or Origin policies. Exercise the actual relevant entry path.
- Assert response ownership, errors and persistent effects, not only HTTP 200. Preserve RequestValues/NDJSON behavior via [rest-api-conventions](../rest-api-conventions/SKILL.md).

## Configuration and context

Use ApplicationContextRunner for actual binding and conditional wiring behavior: enabled versus disabled integration, invalid units/ranges, missing required enabled credentials and the deployed override/configtree path.

Keep shared default/validation definitions as the production source. Do not write assertions that only re-pin an incidental default value or copy a configuration file's text. Redaction tests verify that a real diagnostic path does not leak a synthetic secret.

## Storage and transactions

Use real disposable MySQL/Redis for database-specific schema, locks/CAS, rollback, serialization, expiry and recovery. H2 and mocks can cover narrower behavior but cannot establish MySQL DDL or contention correctness.

Reuse the project's isolated fixture setup. If new Testcontainers infrastructure is actually needed, agree that separately; do not copy the upstream PostgreSQL/JPA dependency snippet into this JDBC/MySQL project.

Observe committed data from an independent transaction when checking rollback or after-commit effects. A test-wide rollback can hide delivery behavior. For current TransactionTemplate-based code, exercise that real path instead of inventing a proxy-only test.

## Protocol fixtures and runtime proof

Use the existing process-local production gateway fixtures/ExchangeFunction for customer protocols. Do not call customer upstreams, control real devices or create real tasks/notifications during engineering tests. Isolated infrastructure/API contracts may bind their own dedicated loopback ports with clear ownership.

The standard Maven command is in root AGENTS.md. Preserve conditional integration-test gates and report skips as unexercised, not passed. Significant behavior changes additionally require a real isolated smoke run; frontend acceptance uses the actual UI and business acceptance uses the agreed real WeChat flow.

## Provenance

Adapted from the Boot 3 testing-pyramid skill at the upstream ref in skills-lock.json. Generic order/JWT/internal-controller examples were omitted; use this repository's public-entry fixtures instead. License: [MIT](../LICENSE.spring-boot-skills).
