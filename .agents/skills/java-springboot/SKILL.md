---
name: java-springboot
description: 'Write and review this existing Spring Boot 3 backend: module-local layering, JDBC transactions, WebClient, typed configuration and HTTP contracts. Read the repository skill contract before topic references.'
---

# Spring Boot application development

Read [the project contract](../PROJECT.md) first; it governs every reference below. This repository targets Boot 3.5, Framework 6, Security 6 and Jackson 2. Imported reference files also contain Boot 4 examples for other projects: only their Boot 3 branches apply here.

## Before writing code

1. **Detect, do not assume:** read `pom.xml`/`build.gradle(.kts)` for the Spring Boot version, Java version, starters in use (`spring-boot-starter-web` is Boot 3, `spring-boot-starter-webmvc` is Boot 4), persistence (JPA, JDBC), the migration tool, the test setup, and the package layout and naming conventions of the existing code. Match them.
2. **Keep the approved Boot 3 stack.** Do not upgrade Spring Boot, Java or dependencies as part of a feature or standards cleanup.
3. **Ask before adding infrastructure:** a broker (Kafka, RabbitMQ), a cache provider, a new database, or a scheduler/async setup. Add only what the user requested, with the Boot-managed version (no explicit version for BOM-managed artifacts, never `latest`).
4. **Verify the changed surface.** Application code changes use the Maven commands and runtime smoke requirements in root AGENTS.md. Documentation-only skill changes validate discovery, metadata and references; read-only reviews report only checks actually exercised. Integration tests need Docker only when their fixture uses it.

## Router

| Task | Read |
| --- | --- |
| Repositories, projections, pagination, N+1, `@Transactional`, auditing, Flyway/Liquibase, Spring Data JDBC | `references/data.md` |
| Controllers, strict validation, existing errors, `RestClient`, `@HttpExchange`, OpenAPI | Read `../rest-api-conventions/SKILL.md` first, then only compatible Boot 3 sections of `references/rest-api.md` |
| Kafka/AMQP, `@Cacheable`/Caffeine, `@Scheduled`, `@Async`, virtual threads, outbox | `references/messaging-caching.md` |
| `@ConfigurationProperties`, profiles, secrets, config import, Docker Compose / Testcontainers at dev time | `references/configuration.md` |
| AOT, CDS/AppCDS, lazy initialization, native image | `references/aot-and-startup.md` |

Use the six locally installed topic skills listed in [the project contract](../PROJECT.md) when their triggers match. Optional upstream topics are not authorization to add JPA, brokers, native-image infrastructure or another security mechanism.

## Core rules

- **Structure and layers:** package by feature; constructor injection; controllers return DTOs/records, never entities; services own the transaction.
- **Validation and errors:** preserve the existing error module and `{code,message,recovery?}` response. Apply `@Valid` only where typed binding preserves strict input semantics; do not replace the protocol with `ProblemDetail`.
- **Jackson:** use Jackson 2 (`com.fasterxml.jackson.databind.*`, `ObjectMapper`) under the Boot 3 BOM.
- **Slice-test gotcha:** put `@EnableJpaAuditing`, `@EnableCaching`, `@EnableAsync`, `@EnableScheduling` on dedicated `@Configuration` classes, not on the `@SpringBootApplication` class, or slices such as `@DataJpaTest` fail to start (for example, no `CacheManager`). Slices do not scan `@Configuration` classes: `@Import` the one a test needs.
- **Proxied beans:** `@Cacheable`, `@Async` and `@Transactional` work through proxies: they do nothing on self-invocation or on `private`/`final` methods; test them through the injected bean.

## Checklist

- [ ] Versions, build tool and conventions detected; starters match the Boot major
- [ ] No new infrastructure or dependency without the user's agreement
- [ ] Appropriate verification for the changed surface completed and accurately reported; secrets absent from code, properties and logs

See also: [Boot 3.5 reference](https://docs.spring.io/spring-boot/3.5/reference/), [Framework 6.2 reference](https://docs.spring.io/spring-framework/reference/6.2/). Existing upstream provenance stays in skills-lock.json; license: [MIT](../LICENSE.awesome-copilot).
