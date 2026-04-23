package com.example.chat.session.dto;

import java.util.List;

public record SessionListResponse(
        List<SessionSummary> content,
        long total,
        int page,
        int size
) {
    public static SessionListResponse of(List<SessionSummary> content, long total, int page, int size) {
        return new SessionListResponse(content, total, page, size);
    }
}
