package com.ehe.iotagent.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;

/** Shared identity rule for confirmation and interrupted-confirmation recovery. */
public final class TaskPhaseIds {
    private TaskPhaseIds() { }

    /** Mutates an owned payload; existing durable phase identities never change. */
    public static void assignMissing(ObjectNode payload) {
        for (JsonNode item : payload.path("phases")) {
            if (item instanceof ObjectNode phase
                    && (!phase.path("phaseId").isTextual() || phase.path("phaseId").asText().isBlank())) {
                phase.put("phaseId", UUID.randomUUID().toString());
            }
        }
    }
}
