---
name: spring-modulith
description: >
  Use when reviewing Spring Boot 3 module APIs, internal-type leakage, dependency cycles and architecture verification. Reuse this project's ArchUnit rules; adopting Modulith dependencies or durable events is a separate decision.
---

# Spring Modulith

Read [the project contract](../PROJECT.md) first. This skill supplies modularity guidance; it does not install Spring Modulith into the application.

## Spring Boot 3 baseline

If separately approved, the compatible baseline is Spring Modulith 1.4 with Boot 3.5; check the official matrix before adding dependencies. Current enforcement remains ArchUnit.

## Apply within the existing architecture

Inspect the application root package, module boundaries and current dependency graph.
Use direct subpackages as modules; keep their API at the module root and implementation in
internal subpackages. Expose a named interface only for an intentional shared API.
Use allowedDependencies when the architecture needs explicit dependency constraints.
Avoid creating a generic shared module that every feature depends on.

## Verify boundaries

First extend the existing ArchitectureTest: check complete dependencies, public signatures and test/helper callers, not only method/field accesses or class-name suffixes. Preserve package-root entry points and cycle checks.

Only after a separate adoption decision, consider spring-modulith-starter-test and ApplicationModules.verify(). ApplicationModuleTest examples do not permit tests to import internal classes. A passing structure check does not prove transactional or delivery correctness.

See [good module verification](examples/good-module-test.java) and
[bad cross-module dependency](examples/bad-module-dependency.java).

## Reliable events

This section applies only when a real requirement and explicit adoption decision call for Modulith events. Existing task checkpoints and report/notification attempts remain the recovery mechanism; do not duplicate them with a second event system.

Publish immutable event payloads containing IDs and necessary facts, not managed JPA entities.
Use ApplicationModuleListener for asynchronous transactional listeners when that timing fits.
For delivery that must survive a process crash, configure a persistent event publication
registry using the chosen JDBC/JPA starter and manage its schema through migrations.
An in-memory registry and a plain after-commit callback are not durable delivery.

Make handlers idempotent: a crash after the business effect but before recording completion
can cause redelivery. Define retry/republication and retention explicitly, and test failed
listeners and application restarts. For external brokers, use supported event externalization
with persistent publication tracking; do not promise end-to-end exactly-once processing.

## Gotchas

- Agent imports a repository from another module's internal package - call its public API or consume an event.
- Agent adds Modulith to a simple unrelated task - preserve the requested scope.
- Agent assumes ApplicationModuleListener alone guarantees durability - configure persistent publication tracking.
- Agent publishes an entity with lazy associations - publish an immutable event contract.
- Agent tests only the happy path - verify cycles, module access and event redelivery.

## Official sources

- [Module verification, Modulith 1.4](https://docs.spring.io/spring-modulith/reference/1.4/verification.html)
- [Events and publication registry, Modulith 1.4](https://docs.spring.io/spring-modulith/reference/1.4/events.html)
- [Compatibility, Modulith 1.4](https://docs.spring.io/spring-modulith/reference/1.4/appendix.html)
