package com.ehe.iotagent.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared derivation so default in-memory reads and MySQL agree on semantics. */
final class AuditQuerySupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AuditQuerySupport() {
    }

    static List<AuditEventFact> select(List<AuditEventFact> events, AuditEventQuery query) {
        List<AuditEventFact> matched = new ArrayList<>();
        for (AuditEventFact event : events) {
            Map<String, Object> payload = payload(event);
            String principal = text(payload.get("principalId"));
            String realm = text(payload.get("identityRealm"));
            if (query.unresolvedOnly() && principal != null && realm != null) continue;
            if (!query.unresolvedOnly() && query.principalId() != null
                    && !query.principalId().equals(principal)) continue;
            if (query.identityRealm() != null && !query.identityRealm().equals(realm)) continue;
            if (query.source() != null && !query.source().equals(text(payload.get("source")))) continue;
            if (query.eventType() != null && !query.eventType().equals(event.eventType())) continue;
            Instant at = event.createdAt();
            if (at == null) continue;
            if (query.fromAt() != null && at.isBefore(query.fromAt())) continue;
            if (query.toAt() != null && at.isAfter(query.toAt())) continue;
            if (query.cursorAt() != null
                    && (at.isAfter(query.cursorAt())
                        || (at.equals(query.cursorAt()) && event.rowId() >= query.cursorId()))) continue;
            if (!resultMatches(payload, event.eventType(), query)) continue;
            matched.add(event);
        }
        matched.sort(Comparator
                .comparing(AuditEventFact::createdAt, Comparator.reverseOrder())
                .thenComparing(Comparator.comparingLong(AuditEventFact::rowId).reversed()));
        return matched.size() > query.limit() ? new ArrayList<>(matched.subList(0, query.limit())) : matched;
    }

    private static boolean resultMatches(Map<String, Object> payload, String eventType, AuditEventQuery query) {
        if (query.statuses().isEmpty() && query.eventTypes().isEmpty() && !query.excludeEventTypes()) return true;
        String status = text(payload.get("status"));
        if (status != null) return query.statuses().contains(status);
        boolean typeKnown = query.eventTypes().contains(eventType == null ? "" : eventType);
        return query.excludeEventTypes() != typeKnown;
    }

    static Map<String, String> knownNames(java.util.stream.Stream<AuditEventFact> events,
            java.util.Set<String> mobileNumbers) {
        Map<String, java.util.Set<String>> names = new LinkedHashMap<>();
        events.forEach(event -> {
            Map<String, Object> body = payload(event);
            String source = text(body.get("source"));
            if (text(body.get("principalId")) == null || text(body.get("identityRealm")) == null
                    || !("company".equals(source) || "office".equals(source))) return;
            String phone = text(body.get("mobileNumber")), name = text(body.get("displayName"));
            if (phone != null && mobileNumbers.contains(phone) && name != null) {
                names.computeIfAbsent(phone, ignored -> new java.util.HashSet<>()).add(name);
            }
        });
        Map<String, String> result = new LinkedHashMap<>();
        names.forEach((phone, values) -> {
            if (values.size() == 1) result.put(phone, values.iterator().next());
        });
        return result;
    }

    static AuditMemberIndex index(String tenantCode, List<AuditEventFact> events,
            String identityRealm, String principalId, int limit, int offset) {
        Map<String, AuditMember> members = new LinkedHashMap<>();
        for (AuditEventFact event : events) {
            Map<String, Object> payload = payload(event);
            String realm = text(payload.get("identityRealm"));
            String principal = text(payload.get("principalId"));
            if (identityRealm != null && !identityRealm.equals(realm)) continue;
            if (principalId != null && !principalId.equals(principal)) continue;
            boolean resolved = realm != null && principal != null;
            String key = resolved ? realm + "\u0000" + principal : "\u0000unresolved";
            String displayName = text(payload.get("displayName"));
            String eventDisplayName = displayName == null ? text(payload.get("creatorName")) : displayName;
            String mobileNumber = text(payload.get("mobileNumber"));
            AuditMember member = members.computeIfAbsent(key, ignored -> new AuditMember(tenantCode,
                    realm, principal, eventDisplayName, mobileNumber, resolved, 0, new LinkedHashMap<>(),
                    event.createdAt(), event.createdAt()));
            Map<String, Long> sources = new LinkedHashMap<>(member.sources());
            String source = text(payload.get("source"));
            sources.merge(source == null ? "unknown" : source, 1L, Long::sum);
            Instant at = event.createdAt();
            members.put(key, new AuditMember(tenantCode, realm, principal,
                    member.displayName() == null ? eventDisplayName : member.displayName(),
                    member.mobileNumber() == null ? mobileNumber : member.mobileNumber(),
                    resolved, member.eventCount() + 1, sources,
                    at != null && member.firstAt() != null && at.isBefore(member.firstAt()) ? at : member.firstAt(),
                    at != null && member.lastAt() != null && at.isAfter(member.lastAt()) ? at : member.lastAt()));
        }
        List<AuditMember> sorted = new ArrayList<>(members.values());
        sorted.sort(Comparator.comparing(AuditMember::lastAt,
                Comparator.nullsFirst(Comparator.reverseOrder())));
        List<AuditMember> page = offset >= sorted.size()
                ? List.of()
                : List.copyOf(sorted.subList(offset, Math.min(offset + limit, sorted.size())));
        return new AuditMemberIndex(page, sorted.size());
    }

    private static Map<String, Object> payload(AuditEventFact event) {
        String json = event.payloadJson();
        if (json == null || json.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception unreadable) {
            return Map.of();
        }
    }

    private static String text(Object value) {
        if (value instanceof String string && !string.isBlank()) return string;
        return null;
    }
}
