package com.example.chat.session.repository;

import com.example.chat.session.domain.SessionStatus;
import com.example.chat.session.dto.SessionListResponse;

import java.time.LocalDateTime;

// 동적 세션 검색용 프래그먼트. SessionRepository에 다중 인터페이스 상속으로 조합된다.
public interface SessionQueryRepository {

    SessionListResponse search(SessionStatus status,
                                LocalDateTime from,
                                LocalDateTime to,
                                String participant,
                                int page,
                                int size);
}
