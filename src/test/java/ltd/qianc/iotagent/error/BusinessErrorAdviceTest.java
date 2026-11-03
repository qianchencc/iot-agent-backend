package ltd.qianc.iotagent.error;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(BusinessErrorAdviceTest.TestErrorController.class)
class BusinessErrorAdviceTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void mapsBusinessErrorToConfiguredStatusAndBody() throws Exception {
        mockMvc.perform(get("/test/errors/business"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DRAFT_CONFLICT"))
                .andExpect(jsonPath("$.message").value("草案已过期"))
                .andExpect(jsonPath("$.recovery").value("请重新发起"));
    }

    @Test
    void hidesUnexpectedFailureDetailsFromCaller() throws Exception {
        mockMvc.perform(get("/test/errors/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL"))
                .andExpect(jsonPath("$.message").value("原因未确认"))
                .andExpect(jsonPath("$.recovery").doesNotExist());
    }

    @Test
    void unknownRouteMatchesRuntimeNotFoundEnvelope() throws Exception {
        mockMvc.perform(get("/test/errors/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Route GET:/test/errors/missing not found"))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.statusCode").value(404));
    }

    @RestController
    static class TestErrorController {

        @GetMapping("/test/errors/business")
        void business() {
            throw new BusinessException("DRAFT_CONFLICT", "草案已过期", "请重新发起", 409);
        }

        @GetMapping("/test/errors/unexpected")
        void unexpected() {
            throw new IllegalStateException("敏感内部细节");
        }
    }
}
