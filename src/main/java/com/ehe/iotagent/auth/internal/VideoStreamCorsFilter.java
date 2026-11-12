package com.ehe.iotagent.auth.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsProcessor;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Cross-origin support for exactly one browser route: the short-lived video
 * stream address consumed by the separately served player page. A browser
 * preflight must succeed before the authenticated fetch is attempted, but the
 * actual GET still passes through {@link AgentIdentityFilter} and therefore
 * keeps the real Authorization + Tenantcode identity.
 *
 * <p>Only one explicit origin may be configured. Wildcards, paths, user info,
 * cookies and other Agent routes are not supported, so this never becomes a
 * general Agent API CORS policy. Without configuration the filter is absent and
 * the route keeps its previous fail-closed behaviour.
 */
final class VideoStreamCorsFilter extends OncePerRequestFilter {

    private static final Pattern VIDEO_STREAM_PATH =
            Pattern.compile("^/api/agent/devices/[^/]+/video-stream$");
    private final CorsConfiguration configuration;
    private final CorsProcessor processor = new DefaultCorsProcessor();

    private VideoStreamCorsFilter(CorsConfiguration configuration) {
        this.configuration = configuration;
    }

    /** Returns null when cross-origin video playback is not configured. */
    static VideoStreamCorsFilter create(String configuredOrigin) {
        String origin = explicitOrigin(configuredOrigin);
        if (origin.isEmpty()) return null;
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(origin));
        configuration.setAllowedMethods(List.of("GET", "OPTIONS"));
        // The player fetches with cache: 'no-store'; some browsers surface the
        // resulting cache hints in the preflight header list.
        configuration.setAllowedHeaders(List.of(
                "Authorization", "Tenantcode", "Cache-Control", "Pragma"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(600L);
        return new VideoStreamCorsFilter(configuration);
    }

    static String explicitOrigin(String configuredOrigin) {
        String candidate = configuredOrigin == null ? "" : configuredOrigin.trim();
        while (candidate.endsWith("/")) candidate = candidate.substring(0, candidate.length() - 1);
        if (candidate.isEmpty()) return "";
        if (candidate.contains("*")) {
            throw new IllegalStateException("agent.video.allowed-origin 必须是一个明确来源，不能使用通配符");
        }
        final URI uri;
        try {
            uri = URI.create(candidate);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalStateException("agent.video.allowed-origin 不是有效来源");
        }
        boolean webScheme = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
        boolean bareOrigin = uri.getHost() != null && uri.getUserInfo() == null
                && (uri.getPath() == null || uri.getPath().isEmpty())
                && uri.getQuery() == null && uri.getFragment() == null;
        if (!webScheme || !bareOrigin) {
            throw new IllegalStateException("agent.video.allowed-origin 须为 scheme://host[:port]，不含路径、参数或用户信息");
        }
        return candidate;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!isVideoStreamRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!processor.processRequest(configuration, request, response)) return;
        if (CorsUtils.isPreFlightRequest(request)) return;
        filterChain.doFilter(request, response);
    }

    private static boolean isVideoStreamRequest(HttpServletRequest request) {
        try {
            return VIDEO_STREAM_PATH.matcher(
                    URLDecoder.decode(request.getRequestURI(), StandardCharsets.UTF_8)).matches();
        } catch (IllegalArgumentException malformedPath) {
            return false;
        }
    }
}
