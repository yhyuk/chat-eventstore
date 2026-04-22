package com.example.chat.common.exception;

public class InvalidSequenceException extends BusinessException {

    private final Long sessionId;
    private final Long sequence;

    public InvalidSequenceException(Long sessionId, Long sequence) {
        super(ErrorCode.INVALID_SEQUENCE,
                "Invalid sequence: sessionId=" + sessionId + ", sequence=" + sequence);
        this.sessionId = sessionId;
        this.sequence = sequence;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public Long getSequence() {
        return sequence;
    }
}
