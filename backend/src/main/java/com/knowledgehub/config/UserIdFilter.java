package com.knowledgehub.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stub authentication: the caller identifies itself with the {@code X-User-Id} header.
 * In production this is replaced by an API gateway / OIDC resource server that sets the
 * principal; everything downstream only depends on {@link UserContext}.
 */
@Component
public class UserIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-User-Id";
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String userId = request.getHeader(HEADER);
        if (userId == null || !VALID.matcher(userId).matches()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("{\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"Missing or invalid X-User-Id header\"}");
            return;
        }
        try {
            UserContext.set(userId);
            chain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }
}
