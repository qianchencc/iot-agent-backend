package com.ehe.iotagent.error;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "iot.identity.verify=false")
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

    @Test
    void malformedJsonObjectIsAClientErrorWithoutLeakingParserDetails() throws Exception {
        mockMvc.perform(post("/api/agent/conversations")
                        .header("Authorization", "Bearer request-validation")
                        .header("Tenantcode", "TEST")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.recovery").doesNotExist());
    }

    @Test
    void missingRequiredCalendarParameterIsAClientError() throws Exception {
        mockMvc.perform(get("/api/agent/regions/region/calendar")
                        .header("Authorization", "Bearer request-validation")
                        .header("Tenantcode", "TEST")
                        .param("from", "2026-11-05"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void unsupportedMethodPreservesStatusAndAllowedMethods() throws Exception {
        mockMvc.perform(post("/test/errors/business"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Allow", org.hamcrest.Matchers.containsString("GET")))
                .andExpect(jsonPath("$.code").value("HTTP_405"));
    }

    @Test
    void unsupportedRequestMediaRemainsAClientError() throws Exception {
        mockMvc.perform(post("/test/errors/json").contentType("text/plain").content("synthetic"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("HTTP_415"));
    }

    @Test
    void unacceptableResponseMediaPreservesNegotiationFailure() throws Exception {
        mockMvc.perform(post("/test/errors/json").contentType("application/json")
                        .accept("text/plain").content("{}"))
                .andExpect(status().isNotAcceptable());
    }

    @RestController
    static class TestErrorController {

        @org.springframework.web.bind.annotation.PostMapping(
                value = "/test/errors/json", consumes = "application/json", produces = "application/json")
        java.util.Map<String, Object> json(@org.springframework.web.bind.annotation.RequestBody
                                          java.util.Map<String, Object> body) {
            return body;
        }

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
