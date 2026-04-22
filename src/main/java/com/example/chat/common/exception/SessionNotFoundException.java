package com.example.chat.common.exception;

public class SessionNotFoundException extends BusinessException {

    public SessionNotFoundException(Long sessionId) {
        super(ErrorCode.SESSION_NOT_FOUND, "Session not found: id=" + sessionId);
    }
}
