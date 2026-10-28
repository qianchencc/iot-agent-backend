package ltd.qianc.iotagent.error;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessErrorAdviceTest {

    private final BusinessErrorAdvice advice = new BusinessErrorAdvice();

    @Test
    void mapsBusinessErrorToConfiguredStatusAndBody() {
        ResponseEntity<ErrorResponse> resp = advice.onBusiness(
                new BusinessException("DRAFT_CONFLICT", "草案已过期", "请重新发起", 409));

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody()).isEqualTo(new ErrorResponse("DRAFT_CONFLICT", "草案已过期", "请重新发起"));
    }

    @Test
    void hidesUnexpectedFailureDetailsFromCaller() {
        ResponseEntity<ErrorResponse> resp = advice.onUnexpected(new IllegalStateException("敏感内部细节"));

        assertThat(resp.getStatusCode().value()).isEqualTo(500);
        assertThat(resp.getBody()).isEqualTo(new ErrorResponse("INTERNAL", "原因未确认", null));
        assertThat(resp.getBody().recovery()).isNull();
    }
}
