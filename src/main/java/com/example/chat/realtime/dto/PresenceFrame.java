package com.example.chat.realtime.dto;

public record PresenceFrame(String userId, String status) {

    public static final String ONLINE = "ONLINE";
    public static final String OFFLINE = "OFFLINE";

    public String frameType() {
        return "PRESENCE";
    }
}
