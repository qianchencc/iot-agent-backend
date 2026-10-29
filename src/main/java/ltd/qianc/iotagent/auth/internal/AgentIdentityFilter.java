package ltd.qianc.iotagent.auth.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import ltd.qianc.iotagent.auth.AccountPrincipalVerifier;
import ltd.qianc.iotagent.auth.PrincipalContext;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.error.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.filter.OncePerRequestFilter;

public final class AgentIdentityFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;
    private final AccountPrincipalVerifier verifier;
    private final boolean verifyIdentity;

    public AgentIdentityFilter(ObjectMapper objectMapper, AccountPrincipalVerifier verifier, boolean verifyIdentity) {
        this.objectMapper = objectMapper;
        this.verifier = verifier;
        this.verifyIdentity = verifyIdentity;
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
            writeError(response, 400, "INVALID_PATH", "请求路径无效");
            return;
        }
        if (!uri.startsWith("/api/agent/")) {
            filterChain.doFilter(request, response);
            return;
        }

        String authorization = request.getHeader("Authorization");
        String tenantCode = request.getHeader("Tenantcode");
        if (authorization == null || authorization.isBlank()
                || tenantCode == null || tenantCode.isBlank()) {
            writeError(response, 401, "UNAUTHORIZED", "缺少登录凭据或园区标识，请重新登录");
            return;
        }

        String source = request.getParameter("source");
        if (source != null && !source.equals("office") && !source.equals("company")) {
            writeError(response, 400, "INVALID_AGENT_SOURCE", "Agent 来源无效");
            return;
        }
        PrincipalInfo presented = new PrincipalInfo(authorization, tenantCode);
        try {
            PrincipalContext.set(verifyIdentity ? verifier.verify(presented) : presented);
            filterChain.doFilter(request, response);
        } catch (BusinessException error) {
            writeError(response, error.httpStatus(), error.code(), error.getMessage());
        } finally {
            PrincipalContext.clear();
        }
    }

    private void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getOutputStream(), new ErrorResponse(code, message, null));
    }
}
