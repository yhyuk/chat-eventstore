package com.example.chat.common.exception;

public class DuplicateEventException extends BusinessException {

    private final Long existingEventId;
    private final Long existingSequence;

    public DuplicateEventException(Long existingEventId, Long existingSequence) {
        super(ErrorCode.DUPLICATE_EVENT,
                "Duplicate event: existingEventId=" + existingEventId
                        + ", existingSequence=" + existingSequence);
        this.existingEventId = existingEventId;
        this.existingSequence = existingSequence;
    }

    public Long getExistingEventId() {
        return existingEventId;
    }

    public Long getExistingSequence() {
        return existingSequence;
    }
}
