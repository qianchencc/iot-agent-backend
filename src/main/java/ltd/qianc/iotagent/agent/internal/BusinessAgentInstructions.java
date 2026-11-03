package ltd.qianc.iotagent.agent.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Java-owned source instructions migrated from the frozen Runtime business contract. */
final class BusinessAgentInstructions {
    static final String OFFICE = load("office-instructions.txt");
    static final String COMPANY = load("company-instructions.txt");
    static final String SHARED = load("shared-instructions.txt");
    static final String COMPANY_TASKS = load("company-task-instructions.txt");
    static final String FINALIZATION = load("finalization-instructions.txt");
    static final String CHECKPOINT = load("checkpoint-instructions.txt");
    static final String COMPACTION = load("compaction-instructions.txt");

    private BusinessAgentInstructions() { }

    private static String load(String resource) {
        try (var stream = BusinessAgentInstructions.class.getResourceAsStream("/agent/" + resource)) {
            if (stream == null) throw new IllegalStateException("Missing business instructions: " + resource);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load business instructions: " + resource, failure);
        }
    }
}
