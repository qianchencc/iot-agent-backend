---
name: configuration-properties
description: >
  Use when introducing or correcting grouped Spring Boot 3 startup configuration, typed binding, validation, profiles or secret injection. Preserve separately managed MySQL business revisions.
---

# Configuration Properties

Read [the project contract](../PROJECT.md) first. Preserve existing property names, explicit enablement and disabled-state fail-closed behavior; classify each value before binding it.

## Spring Boot 3 baseline

Use Java 21 and the project's Boot 3.5 BOM. Register properties inside their owning module; reuse existing typed properties instead of creating a global settings barrel.

## Bind and validate

Inspect existing property prefixes and external deployment configuration before renaming keys.
Use a typed ConfigurationProperties record for a related group of settings. Register it with
EnableConfigurationProperties or ConfigurationPropertiesScan; a record is not automatically a bean.
Use Validated and Jakarta constraints, and include spring-boot-starter-validation. Validate nested
objects with Valid. A single record constructor needs no ConstructorBinding annotation.

Use explicit units for new durations and sizes. Preserve published `*-ms` numeric keys as milliseconds and verify their typed conversion. Provide defaults only when operationally safe; validate required credentials/endpoints for enabled integrations. Keep deliberately disabled capabilities fail closed without demanding unused credentials. NotNull does not reject zero or negative Duration values; add an explicit invariant.

[ClientProperties](examples/ClientProperties.java) and
[its configuration](examples/good-client.yml) show a validated prefix with explicit duration units.
Do not turn a full API token into a record component: generated toString can expose secrets.
Resolve secrets at the integration boundary or use a redacting value type.

## Deployment and overrides

Document the public property keys and verify the actual external YAML/configtree override path. Platform environment overrides are valid native Spring inputs where approved; do not restore dotenv or a custom launcher. MySQL-managed business revisions remain outside startup binding.
Higher-precedence property sources can override packaged defaults; test the intended profile and
override path. Avoid depending on YAML list merging: lists are generally replaced.
Keep profile activation outside documents activated by that same profile.

Do not publish configprops/env actuator endpoints publicly or disable sanitization to debug.
Binding is normally at startup; changing a file or secret does not imply live refresh.
Define restart/rotation behavior when configuration changes.

## Verification

Use ApplicationContextRunner with EnableConfigurationProperties to assert valid binding and
startup failure on missing or invalid settings. Test nested validation, unit conversion and the
override path you actually deploy. Include at least one invalid duration test.

## Gotchas

- Agent defines a properties record but never registers it - enable scanning or explicit registration.
- Agent adds Validated without a validation provider - include the validation starter.
- Agent adds a numeric timeout with no unit contract - define units for the new key; preserve existing `*-ms` integer keys and verify millisecond binding.
- Agent adds a default credential - fail fast and inject secrets externally.
- Agent logs the full bound configuration - redact secret-bearing values.

## Official sources

- [Boot 3.5 external configuration](https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html)
- [Configuration property validation](https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.validation)
