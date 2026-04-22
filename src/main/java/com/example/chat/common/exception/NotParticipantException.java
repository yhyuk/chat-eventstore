package com.example.chat.common.exception;

public class NotParticipantException extends BusinessException {

    public NotParticipantException(Long sessionId, String userId) {
        super(ErrorCode.NOT_PARTICIPANT,
                "User is not a participant: sessionId=" + sessionId + ", userId=" + userId);
    }
}
