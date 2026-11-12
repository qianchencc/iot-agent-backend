package com.ehe.iotagent.auth.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import com.ehe.iotagent.error.ErrorResponse;
import org.springframework.web.filter.OncePerRequestFilter;

public final class BridgeAuthenticationFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;
    private final String expectedToken;

    public BridgeAuthenticationFilter(ObjectMapper objectMapper, String expectedToken) {
        this.objectMapper = objectMapper;
        this.expectedToken = expectedToken;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String uri;
        try {
            uri = java.net.URLDecoder.decode(request.getRequestURI(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformedPath) {
            response.setStatus(400);
            response.setContentType("application/json;charset=UTF-8");
            objectMapper.writeValue(response.getOutputStream(),
                    new ErrorResponse("INVALID_PATH", "请求路径无效", null));
            return;
        }
        if (!uri.startsWith("/internal/agent/")) {
            filterChain.doFilter(request, response);
            return;
        }

        String providedToken = request.getHeader("X-Agent-Bridge-Token");
        if (expectedToken.isBlank() || providedToken == null || !expectedToken.equals(providedToken)) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            objectMapper.writeValue(response.getOutputStream(),
                    new ErrorResponse("BRIDGE_UNAUTHORIZED", "内部 Agent bridge 身份无效", null));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
