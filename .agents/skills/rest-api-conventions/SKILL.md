---
name: rest-api-conventions
description: Use when writing or reviewing Spring Boot 3 HTTP controllers, request validation, DTOs, pagination, streaming responses and error translation while preserving this project's published contracts.
---

# HTTP contracts for this backend

Read [the project contract](../PROJECT.md), then [the Java HTTP contract](../../../docs/api/java-contract.md) and the affected endpoint's consumer tests. The Runtime OpenAPI/NDJSON baseline remains authoritative as described in root AGENTS.md.

## Input and response contract

1. Record the current route, method, status, headers, identity scope, success body and error codes before changing an endpoint.
2. Preserve {code,message,recovery?}, existing success shapes, cursor semantics and string IDs. Reuse BusinessException, RequestValues and the error module rather than introducing ApiResponse, ProblemDetail, UUID conversion or a /api/v1 rename.
3. Keep missing, explicit null, quoted numbers, fractional numbers and overflow distinct where the current contract does. Map/JsonNode parsing can be deliberate validation, not missing DTO work.
4. For new typed request DTOs, apply Jakarta Bean Validation only with the validation provider and prove that Jackson 2 scalar conversion cannot widen accepted input. Malformed JSON must remain a client error.
5. Cap query sizes and validate sortable fields using the endpoint's existing policy. Preserve whether an invalid value is rejected or clamped; a Page/Pageable example is not a replacement pagination contract.

## Controller and error policy

Keep identity extraction, Cookie/CSRF/Origin and HTTP mapping in the transport seam. Delegate business policy and HTTP client construction according to [layered-architecture](../layered-architecture/SKILL.md).

Reuse the existing advice and filter error writers. Check malformed bodies, binding/type mismatch, unsupported methods, unsupported media and unknown routes retain intended 4xx statuses and headers rather than falling through a generic 500 handler. Do not create a competing catch-all advice.

Response models exclude internal persistence details, credentials and raw upstream diagnostics. Preserve authorization-dependent omission and partial-result distinctions rather than replacing them with an empty success.

## Streaming

NDJSON and event order are a separate transport contract. Keep heartbeat, cancellation, terminal error and client-disconnect semantics; do not buffer the stream into a normal response envelope. An error after headers are committed must follow the event protocol, not attempt a second HTTP response.

## Verification

Use existing public HTTP application factories and in-process protocol fixtures. Assert consumer-visible body, status and relevant side effects; include an unauthorized request and a meaningful invalid-input boundary for changed behavior.

Retain real security-chain coverage where it establishes Cookie/Origin/CSRF semantics. Contract tests do not prove real WeChat or device acceptance; follow [testing-pyramid](../testing-pyramid/SKILL.md).

## Provenance

Adapted from the Boot 3 rest-api-conventions skill at the upstream ref in skills-lock.json. Incompatible envelope/PageResponse/error-handler templates were omitted to avoid competing wire formats and unsafe diagnostic copying. License: [MIT](../LICENSE.spring-boot-skills).
