package com.example.chat.common.exception;

public class SessionEndedException extends BusinessException {

    public SessionEndedException(Long sessionId) {
        super(ErrorCode.SESSION_ENDED, "Session is already ended: id=" + sessionId);
    }
}
