package ltd.qianc.iotagent.admin.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

public final class AdminBoundaryFilter extends OncePerRequestFilter {
    private final ObjectMapper mapper;
    private final AdminSessionManager sessions;
    private final String origin;

    public AdminBoundaryFilter(ObjectMapper mapper, AdminSessionManager sessions,
            @Value("${admin.origin:http://127.0.0.1:5185}") String origin) {
        this.mapper = mapper;
        this.sessions = sessions;
        this.origin = origin;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri;
        try {
            uri = java.net.URLDecoder.decode(request.getRequestURI(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformedPath) {
            write(response, 400, "INVALID_PATH", "请求路径无效");
            return;
        }
        if (!uri.startsWith("/api/admin/")) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        if (!"GET".equals(request.getMethod()) && !origin.equals(request.getHeader("Origin"))) {
            write(response, 403, "ADMIN_ORIGIN_REJECTED", "请从配置的控制台地址提交");
            return;
        }
        if (uri.equals("/api/admin/login")) {
            chain.doFilter(request, response);
            return;
        }
        String token = token(request);
        if (!sessions.valid(token)) {
            write(response, 401, "ADMIN_UNAUTHORIZED", "请登录管理控制台");
            return;
        }
        if (!"GET".equals(request.getMethod()) && !constantTimeEquals(request.getHeader("X-Admin-CSRF"), sessions.csrf(token))) {
            write(response, 403, "ADMIN_CSRF_REJECTED", "会话验证失败，请重新登录");
            return;
        }
        chain.doFilter(request, response);
    }

    static String token(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (AdminSessionManager.COOKIE.equals(cookie.getName())) return cookie.getValue();
            }
        }
        String header = request.getHeader("Cookie");
        if (header == null) return null;
        for (String part : header.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && AdminSessionManager.COOKIE.equals(pair[0])) return pair[1];
        }
        return null;
    }

    private static boolean constantTimeEquals(String provided, String expected) {
        if (provided == null || expected == null) return false;
        byte[] left = provided.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] right = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(left, right);
    }

    private void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(response.getOutputStream(), Map.of("code", code, "message", message));
    }
}
