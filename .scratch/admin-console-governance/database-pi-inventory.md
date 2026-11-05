## Pi session tables

All four tables are created by `JdbcPiSessionService` when MySQL-backed Pi sessions are enabled; they are not defined in the SQL resource files. Ownership is resolved from the authenticated Java run context, not trusted from Node. The scope key is SHA-256 of the serialized tenant, owner, source and conversation tuple. Node uses the bridge-authenticated Java endpoint and does not connect to MySQL. Evidence: `src/main/java/ltd/qianc/iotagent/pisession/internal/JdbcPiSessionService.java:25-43,70-88`, `src/main/java/ltd/qianc/iotagent/PiSessionConfiguration.java:17-32`, `agent-sidecar/src/mysql-session-repository.ts:24-39,96-106`.

| Table | Key; ownership | Writer, contents and sensitivity | Mutation, expiry and exposure |
|---|---|---|---|
| `pi_session_scopes` | **PK** `scope_key`; auto-increment `id` unique. Direct `tenant_code`, `owner_key`, `source`, `conversation_id`. | Java service inserts on claim and updates `writer_id`, `run_id`, `server_id`, `binding_json`, `epoch`, `version`, `lease_until`. Binding includes run/conversation/turn/tool-context IDs; operational identity metadata is not application-encrypted. | Row locked for each operation. Lease is **600,000 ms**; conflicting live claims rejected, stale writer/epoch/version rejected. Release clears writer/run/binding and sets lease to zero; lease expiry does not delete session data. Admin Pi health exposes aggregate counts, not scope rows. `JdbcPiSessionService.java:25-27,40,70-106,136-156`. |
| `pi_session_documents` | **PK** `(scope_key, document_key)`; `id` unique. Ownership inherited via `scope_key`; `document_key` and `parent_key` are path hashes. | Java virtual filesystem records `path_json`, file/directory `kind`, `next_chunk`, byte `content_size`, modification time. Path is JSON-encoded, not encrypted. | Insert directory/file; update size/chunk pointer; rename updates path and document key; remove deletes document and its chunks. Admin health counts files and invalid journals only. `JdbcPiSessionService.java:41,152-156,161-176,178-215`. |
| `pi_session_chunks` | **PK** `(scope_key, document_key, chunk_no)`; `id` unique. Ownership inherited via scope/document. | Java stores ordered `content_json` as JSON-encoded **plaintext** strings, not encrypted blobs. Pi’s JSONL codec carries session headers and entries in these chunks—not separate header, entry or blob tables. Entries can contain prompts, assistant/tool messages, effective tool arguments, checkpoints, operation records and results; treat as sensitive business content. `agent-sidecar/src/mysql-session-repository.ts:1-9,128-145,171-200`; `agent-sidecar/src/session.ts:15-91`. | Append adds ordered chunk; overwrite deletes existing chunks before writing; rename changes key; remove deletes chunks. Reads page by chunk number and detect missing/order-corrupt journals. `JdbcPiSessionService.java:42,161-176,192-211,217-230`. |
| `pi_session_commits` | **PK** `(scope_key, commit_id)`; `id` unique. Ownership inherited via scope. | Java persists `request_hash` and `response_json` for mutating virtual-filesystem calls. These are operational metadata, not application-encrypted; do not assume responses are safe for unrestricted display. | Insert receipt atomically with write and scope version increment. Same commit/hash returns prior response; same commit with different hash is rejected. No receipt pruning was found in the inspected service. `JdbcPiSessionService.java:43,113-145`; `agent-sidecar/src/mysql-session-repository.ts:96-121`. |

The journal is **not wholly append-only**: normal entry appends coexist with overwrite, rename and remove. Sidecar recovery appends saved tool checkpoints or an interrupted-result marker; it does not automatically replay uncertain external tool effects (`agent-sidecar/src/session.ts:15-55`). ADR0005 says the Java/MySQL implementation was switched in an isolated instance, but business acceptance was not complete; it requires backup before the one-way cutover and leaves old SQLite offline (`docs/adr/0005-java-owned-mysql-pi-session.md:1-20`).

## Local evidence, drift and retention

- Parent-provided read-only snapshot evidence at `.scratch/admin-console-governance/evidence/local-mysql-metadata.json:1-6,3820-4079` reports Pi counts in **business / frozen** respectively: scopes **4/2**, documents **12/6**, chunks **473/217**, commits **477/219**. These are local snapshot counts, not proof of current production behavior or business acceptance.
- The business schema had **27 tables**, the frozen schema **25**; the frozen snapshot lacked `read_snapshot_credential_barriers` and `read_snapshot_recovery_checkpoints`. Current initialization declares both (`src/main/resources/db/mysql/read-snapshot-barrier.sql:1-24`, `src/main/java/ltd/qianc/iotagent/storage/internal/StorageSchemaInitializer.java:17-25`). The metadata evidence is columns and counts, not a complete index, foreign-key, data-quality or migration-success audit.
- Current startup creates `storage_migrations` and, once per `pi-session-mysql-direct-cutover-v1` marker, marks existing conversations cleared while preserving underlying business facts (`StorageSchemaInitializer.java:27-38`). A clean-looking schema alone therefore does not establish backup or restore readiness.
- **Retention:** no scheduled Pi session/chunk/commit purge or backup implementation was found in the inspected persistence code. A lease is a concurrency deadline, **not** a data-retention policy. Explicit document removal and overwrite can delete chunks; scopes and commit receipts have no observed cleanup path. Backup schedule, restore procedure, storage encryption and retention outside this code remain **unknown**, not absent by proof.

## Coverage and safe follow-up

The table inventory comes from static searches for `CREATE TABLE` in production Java/SQL and Pi sidecar persistence, plus the parent’s local metadata/count artifact; it is not a live database inspection by this agent. No tests, builds, service calls, database access, or mutations were run.

For a parent-operated **read-only local** check, restrict output to column names and aggregate counts; do not select paths, content, bindings, ciphertext, hashes or identities. For example, after selecting the intended local schema:

```sql
SELECT TABLE_NAME, COLUMN_NAME
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('pi_session_scopes', 'pi_session_documents',
                     'pi_session_chunks', 'pi_session_commits')
ORDER BY TABLE_NAME, ORDINAL_POSITION;

SELECT COUNT(*) AS invalid_journals
FROM pi_session_documents d
WHERE d.kind = 'file'
  AND (d.next_chunk <> (
         SELECT COUNT(*) FROM pi_session_chunks c
         WHERE c.scope_key = d.scope_key AND c.document_key = d.document_key
       )
       OR EXISTS (
         SELECT 1 FROM pi_session_chunks c
         WHERE c.scope_key = d.scope_key AND c.document_key = d.document_key
           AND (c.chunk_no < 0 OR c.chunk_no >= d.next_chunk)
       ));
```

The integrity-count predicate mirrors the service health check (`JdbcPiSessionService.java:152-156`); it does not validate JSONL semantics, authorization or backup recoverability.
