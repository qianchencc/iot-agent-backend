package ltd.qianc.iotagent.iot;

import java.util.List;
import java.util.UUID;

/** Public candidate projection. It intentionally has no provider/control references. */
public record DeviceCandidateSet(
        String candidateSetId,
        DeviceCandidateQuery query,
        List<AuthorizedDevice> devices,
        boolean complete,
        int totalMatches,
        Groups groups,
        List<NameHintMatch> nameHintMatches,
        List<String> unmatchedNameHints,
        Resolution resolution) {

    public DeviceCandidateSet(String candidateSetId, DeviceCandidateQuery query, List<AuthorizedDevice> devices,
                              boolean complete, int totalMatches) {
        this(candidateSetId, query, devices, complete, totalMatches,
                new Groups(true, List.of(), List.of()), List.of(), List.of(),
                new Resolution("exact", query == null ? null : query.scopeText(), List.of(), List.of()));
    }

    public DeviceCandidateSet {
        candidateSetId = candidateSetId == null || candidateSetId.isBlank() ? UUID.randomUUID().toString() : candidateSetId;
        query = java.util.Objects.requireNonNull(query, "query");
        devices = List.copyOf(java.util.Objects.requireNonNull(devices, "devices"));
        if (totalMatches < devices.size()) throw new IllegalArgumentException("totalMatches is smaller than devices");
        groups = groups == null ? new Groups(true, List.of(), List.of()) : groups;
        nameHintMatches = List.copyOf(nameHintMatches == null ? List.of() : nameHintMatches);
        unmatchedNameHints = List.copyOf(unmatchedNameHints == null ? List.of() : unmatchedNameHints);
        resolution = resolution == null ? new Resolution("exact", query.scopeText(), List.of(), List.of()) : resolution;
    }

    public record Groups(boolean complete, List<Group> locations, List<Group> baseNames) {
        public Groups {
            locations = List.copyOf(locations == null ? List.of() : locations);
            baseNames = List.copyOf(baseNames == null ? List.of() : baseNames);
        }
    }

    public record Group(String label, int count) { }

    public record NameHintMatch(String deviceId, List<String> matchedNameHints) {
        public NameHintMatch {
            matchedNameHints = List.copyOf(matchedNameHints == null ? List.of() : matchedNameHints);
        }
    }

    public record Resolution(String mode, String scopeText, List<String> resolvedScopeTerms, List<Alternative> alternatives) {
        public Resolution {
            resolvedScopeTerms = List.copyOf(resolvedScopeTerms == null ? List.of() : resolvedScopeTerms);
            alternatives = List.copyOf(alternatives == null ? List.of() : alternatives);
        }
    }

    public record Alternative(String label, int count, double score) { }
}
