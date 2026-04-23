package com.example.chat.session.repository;

import com.example.chat.session.domain.SessionStatus;
import com.example.chat.session.dto.SessionListResponse;

import java.time.LocalDateTime;

// Fragment for dynamic session search, composed into SessionRepository via multi-interface inheritance.
public interface SessionQueryRepository {

    SessionListResponse search(SessionStatus status,
                                LocalDateTime from,
                                LocalDateTime to,
                                String participant,
                                int page,
                                int size);
}
