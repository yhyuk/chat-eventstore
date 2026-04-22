package com.example.chat.session.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record JoinSessionRequest(
        @NotBlank
        @Size(max = 64)
        String userId
) {
}
