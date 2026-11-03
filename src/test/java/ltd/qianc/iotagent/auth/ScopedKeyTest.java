package ltd.qianc.iotagent.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ScopedKeyTest {

    private static final String OWNER = "e16621b9e9f6fed4219681da302a5cb0933b0e18f17eefd33144f66048e9f8fc";

    @Test
    void shortKeysStayVerbatim() {
        assertThat(ScopedKey.of("XSJ", OWNER, "turn-1"))
                .isEqualTo("XSJ\u0000" + OWNER + "\u0000turn-1");
    }

    @Test
    void runtimeDraftKeysFitTheDurableColumn() {
        // Runtime sidecar idempotency key shape: "<turnId>:<toolCallId>".
        String sidecarKey = "5b0a7a4e-7f3a-4d54-bd2a-9d0f3be6c1aa:call_510590ba51f14b84b6d04203";

        String key = ScopedKey.of("XSJ", OWNER, "office", sidecarKey);

        assertThat(key.length()).isLessThanOrEqualTo(128);
        assertThat(ScopedKey.of("XSJ", OWNER, "office", sidecarKey)).isEqualTo(key);
    }

    @Test
    void foldedKeysKeepRecipientsAndCallersApart() {
        String longTail = "x".repeat(80);

        assertThat(ScopedKey.of("XSJ", OWNER, "office", longTail))
                .isNotEqualTo(ScopedKey.of("XSJ", OWNER, "company", longTail))
                .isNotEqualTo(ScopedKey.of("OTHER", OWNER, "office", longTail));
    }
}
