package com.example.chat.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MDC filter that injects requestId, sessionId, and userId into the MDC context
 * for each HTTP request. Uses targeted remove (not MDC.clear()) to preserve
 * Micrometer traceId/spanId propagated via separate mechanisms.
 */
public class MdcFilter extends OncePerRequestFilter {

    private static final Pattern SESSION_ID_PATTERN =
            Pattern.compile("/sessions/([^/]+)");

    private static final String KEY_REQUEST_ID = "requestId";
    private static final String KEY_SESSION_ID = "sessionId";
    private static final String KEY_USER_ID = "userId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        List<String> keysAdded = new ArrayList<>();

        try {
            // Always add requestId
            MDC.put(KEY_REQUEST_ID, UUID.randomUUID().toString());
            keysAdded.add(KEY_REQUEST_ID);

            // Extract sessionId from URL path (e.g., /sessions/{id}/...)
            String path = request.getRequestURI();
            if (path != null) {
                Matcher matcher = SESSION_ID_PATTERN.matcher(path);
                if (matcher.find()) {
                    MDC.put(KEY_SESSION_ID, matcher.group(1));
                    keysAdded.add(KEY_SESSION_ID);
                }
            }

            // Extract userId from query param or header
            String userId = request.getParameter("userId");
            if (userId == null || userId.isBlank()) {
                userId = request.getHeader("X-User-Id");
            }
            if (userId != null && !userId.isBlank()) {
                MDC.put(KEY_USER_ID, userId);
                keysAdded.add(KEY_USER_ID);
            }

            filterChain.doFilter(request, response);
        } finally {
            // Targeted remove to preserve Micrometer traceId/spanId
            for (String key : keysAdded) {
                MDC.remove(key);
            }
        }
    }
}
