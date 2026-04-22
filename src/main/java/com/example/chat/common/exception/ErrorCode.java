package com.example.chat.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "Session not found"),
    SESSION_ENDED(HttpStatus.CONFLICT, "Session is already ended"),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Invalid request"),
    INVALID_SEQUENCE(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid sequence"),
    NOT_PARTICIPANT(HttpStatus.FORBIDDEN, "Not a participant of the session"),
    DUPLICATE_EVENT(HttpStatus.OK, "Duplicate event ignored"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
