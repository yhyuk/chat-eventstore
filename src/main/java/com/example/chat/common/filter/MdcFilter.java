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
 * 각 HTTP 요청에 requestId, sessionId, userId를 MDC에 주입한다.
 * Micrometer가 별도 메커니즘으로 전파한 traceId/spanId를 보존하기 위해
 * MDC.clear() 대신 키 단위 remove를 사용한다.
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
            MDC.put(KEY_REQUEST_ID, UUID.randomUUID().toString());
            keysAdded.add(KEY_REQUEST_ID);

            // URL 경로에서 sessionId 추출 (예: /sessions/{id}/...)
            String path = request.getRequestURI();
            if (path != null) {
                Matcher matcher = SESSION_ID_PATTERN.matcher(path);
                if (matcher.find()) {
                    MDC.put(KEY_SESSION_ID, matcher.group(1));
                    keysAdded.add(KEY_SESSION_ID);
                }
            }

            // 쿼리 파라미터 우선, 없으면 헤더에서 userId 추출
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
            // Micrometer traceId/spanId 보존을 위해 키 단위로 제거
            for (String key : keysAdded) {
                MDC.remove(key);
            }
        }
    }
}
