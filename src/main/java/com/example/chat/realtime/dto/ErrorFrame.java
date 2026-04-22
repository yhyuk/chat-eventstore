package com.example.chat.realtime.dto;

public record ErrorFrame(String code, String message) {

    public String frameType() {
        return "ERROR";
    }
}
