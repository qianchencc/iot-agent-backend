package ltd.qianc.iotagent.admin.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Fields snapshotted by Java at each run; deployment settings still require restart. */
final class LiveAgentSettings {
    private static final Set<String> FIELDS = Set.of(
            "modelBaseUrl", "modelId", "modelApiKey", "contextWindow", "maxOutputTokens", "reasoning",
            "thinkingLevel", "supportsDeveloperRole", "supportsReasoningEffort", "supportsUsageInStreaming",
            "maxTokensField", "maxAgentTurns", "maxToolCalls", "officeBaseInstructions", "companyBaseInstructions",
            "sharedInstructions", "companyTaskInstructions", "checkpointInstructions", "finalizationInstructions",
            "compactionInstructions", "officeInstructions", "companyInstructions",
            "replyInstructions", "runTimeoutMs", "heartbeatMs", "scanIntervalMs");

    private LiveAgentSettings() { }

    static Map<String, Object> effective(Map<String, Object> started, Map<String, Object> saved) {
        Map<String, Object> result = new LinkedHashMap<>(started);
        for (String field : FIELDS) {
            if (saved.containsKey(field)) result.put(field, saved.get(field));
        }
        return result;
    }
}
