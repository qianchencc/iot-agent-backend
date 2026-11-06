---
name: layered-architecture
description: Use when reviewing or changing Controller, application-service, persistence or bean-assembly responsibilities inside this project's Spring Boot 3 deep modules.
---

# Layering inside deep modules

Read [the project contract](../PROJECT.md) first. This is a project-adapted Boot 3 skill, not an instruction to convert the application to global MVC packages.

## Inspect before changing

1. Follow the HTTP endpoint through its package-root entry point to internal implementation and persistence.
2. Distinguish transport parsing, application orchestration, domain policy, persistence and bean assembly.
3. Name the behavior that currently changes in several places. Extract only when it hides that complexity or serves a real varying dependency; a forwarding class is not a layer improvement.

## Module shape

- Public code entry points stay at ltd.qianc.iotagent.<module>; implementation stays below internal or another private-to-module subpackage.
- Controller/service/mapper/domain organization, when useful, stays inside the owning module. Existing facades and factories are valid application and construction entry points.
- Keep public constructors, generic parameters, return types and exceptions free of internal implementation types. Test callers cross the same public interface.
- Preserve constructor injection. Module @Configuration classes may assemble application services and adapters; do not force assembly into one global config package.

## HTTP responsibilities

Controllers bind validated HTTP input, obtain verified identity and map the existing response contract. They may call an existing behavior-rich public facade, reader or store; the class name alone does not prove a repository leak.

Move client construction, upstream error translation, configuration-adoption decisions and reusable read/cache eligibility policy into the owning module's configuration or implementation. Keep Cookie, Origin, HTTP status and streaming transport concerns at the appropriate transport seam.

Reuse the error module's advice. A local catch is justified only for a deliberate protocol/recovery distinction; do not erase status or expose raw upstream failures.

## Application and persistence responsibilities

- Application use cases own multi-step policy and transaction intent. Keep network effects outside database critical sections.
- JDBC SQL and MyBatis mappings are normal persistence implementations here. Do not introduce JpaRepository or prohibit SQL to satisfy a generic example.
- Keep response DTO mapping where it gives locality. Records, static factories, small projectors and direct loops are all valid; choose clarity and avoid unnecessary allocation.
- Do not add Lombok, a mapper library, one interface per class or a Service that only forwards an existing facade call.
- Existing TransactionTemplate and TaskAtomicTransactions are valid; use [transactional-patterns](../transactional-patterns/SKILL.md) for changes to atomicity.

## Verification and review

Exercise the public interface and its observable authorization, error and persistence behavior. Keep strict RequestValues semantics when adding DTOs; use [rest-api-conventions](../rest-api-conventions/SKILL.md).

Check module dependencies and public signatures, not only class size or annotation count. Report confirmed violations separately from possible Feature Envy, Divergent Change or Middle Man smells. A large facade is a review lead, not evidence that a rewrite is necessary.

## Provenance

Adapted from the Boot 3 layered-architecture skill at the upstream ref in skills-lock.json. Incompatible JPA/Lombok scaffolding examples were omitted; use existing project implementations as examples. License: [MIT](../LICENSE.spring-boot-skills).
