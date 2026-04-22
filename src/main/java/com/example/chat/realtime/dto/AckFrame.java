package com.example.chat.realtime.dto;

public record AckFrame(
        String clientEventId,
        String status,
        Long existingEventId,
        Long existingSequence) {

    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_DUPLICATE = "DUPLICATE_IGNORED";

    public String frameType() {
        return "ACK";
    }

    public static AckFrame accepted(String clientEventId) {
        return new AckFrame(clientEventId, STATUS_ACCEPTED, null, null);
    }

    public static AckFrame duplicate(String clientEventId, Long existingEventId, Long existingSequence) {
        return new AckFrame(clientEventId, STATUS_DUPLICATE, existingEventId, existingSequence);
    }
}
