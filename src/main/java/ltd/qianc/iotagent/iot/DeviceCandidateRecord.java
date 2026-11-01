package ltd.qianc.iotagent.iot;

import java.util.List;

/**
 * The persisted candidate-set fact. Members retain control/physical bindings and
 * are only used by server-side materialization; they are never an HTTP view.
 */
public record DeviceCandidateRecord(
        String candidateSetId,
        String conversationId,
        String tenantCode,
        String ownerKey,
        IotSource source,
        String catalogFingerprint,
        DeviceCandidateQuery query,
        List<AuthorizedDevice> members,
        boolean complete,
        int totalMatches,
        String createdAt) {

    public DeviceCandidateRecord {
        if (candidateSetId == null || candidateSetId.isBlank()) {
            throw new IllegalArgumentException("candidateSetId is required");
        }
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId is required");
        }
        if (tenantCode == null || tenantCode.isBlank()) {
            throw new IllegalArgumentException("tenantCode is required");
        }
        if (ownerKey == null || ownerKey.isBlank()) {
            throw new IllegalArgumentException("ownerKey is required");
        }
        if (source == null) throw new IllegalArgumentException("source is required");
        if (catalogFingerprint == null || catalogFingerprint.isBlank()) {
            throw new IllegalArgumentException("catalogFingerprint is required");
        }
        query = java.util.Objects.requireNonNull(query, "query");
        members = List.copyOf(members == null ? List.of() : members);
        if (totalMatches < members.size()) throw new IllegalArgumentException("totalMatches is smaller than members");
        if (createdAt == null || createdAt.isBlank()) createdAt = java.time.Instant.now().toString();
    }
}
