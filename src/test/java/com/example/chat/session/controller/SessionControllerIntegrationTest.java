package com.example.chat.session.controller;

import com.example.chat.common.AbstractWebIntegrationTest;
import com.example.chat.session.domain.Session;
import com.example.chat.session.domain.SessionStatus;
import com.example.chat.session.repository.ParticipantRepository;
import com.example.chat.session.repository.SessionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SessionControllerIntegrationTest extends AbstractWebIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private ParticipantRepository participantRepository;

    @BeforeEach
    void cleanDb() {
        participantRepository.deleteAll();
        sessionRepository.deleteAll();
    }

    @Test
    void createSession_201() throws Exception {
        MvcResult result = mockMvc.perform(post("/sessions"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").isNumber())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        Long sessionId = body.get("sessionId").asLong();

        Session saved = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(saved.getVersion()).isEqualTo(0L);
        assertThat(saved.getLastSequence()).isEqualTo(0L);
        assertThat(saved.getEndedAt()).isNull();
    }

    @Test
    void joinSession_200() throws Exception {
        Long sessionId = createSessionViaApi();

        mockMvc.perform(post("/sessions/" + sessionId + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId))
                .andExpect(jsonPath("$.userId").value("alice"))
                .andExpect(jsonPath("$.joinedAt").isNotEmpty());

        assertThat(participantRepository.findAll()).hasSize(1);
    }

    @Test
    void joinSession_idempotent_200() throws Exception {
        Long sessionId = createSessionViaApi();

        MvcResult first = mockMvc.perform(post("/sessions/" + sessionId + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"alice\"}"))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult second = mockMvc.perform(post("/sessions/" + sessionId + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"alice\"}"))
                .andExpect(status().isOk())
                .andReturn();

        LocalDateTime firstJoinedAt = parseTimestamp(first.getResponse().getContentAsString(), "joinedAt");
        LocalDateTime secondJoinedAt = parseTimestamp(second.getResponse().getContentAsString(), "joinedAt");
        assertThat(secondJoinedAt).isEqualTo(firstJoinedAt);
        assertThat(participantRepository.findAll()).hasSize(1);
    }

    @Test
    void joinSession_sessionNotFound_404() throws Exception {
        mockMvc.perform(post("/sessions/999999/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"alice\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void joinSession_sessionEnded_409() throws Exception {
        Long sessionId = createSessionViaApi();
        mockMvc.perform(post("/sessions/" + sessionId + "/end"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/sessions/" + sessionId + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"alice\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_ENDED"));
    }

    @Test
    void joinSession_blankUserId_400() throws Exception {
        Long sessionId = createSessionViaApi();

        mockMvc.perform(post("/sessions/" + sessionId + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void joinSession_malformedJson_400() throws Exception {
        Long sessionId = createSessionViaApi();

        mockMvc.perform(post("/sessions/" + sessionId + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void endSession_200() throws Exception {
        Long sessionId = createSessionViaApi();

        mockMvc.perform(post("/sessions/" + sessionId + "/end"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId))
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.endedAt").isNotEmpty());

        Session saved = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SessionStatus.ENDED);
        assertThat(saved.getEndedAt()).isNotNull();
    }

    @Test
    void endSession_idempotent_200() throws Exception {
        Long sessionId = createSessionViaApi();

        MvcResult first = mockMvc.perform(post("/sessions/" + sessionId + "/end"))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult second = mockMvc.perform(post("/sessions/" + sessionId + "/end"))
                .andExpect(status().isOk())
                .andReturn();

        LocalDateTime firstEndedAt = parseTimestamp(first.getResponse().getContentAsString(), "endedAt");
        LocalDateTime secondEndedAt = parseTimestamp(second.getResponse().getContentAsString(), "endedAt");
        assertThat(secondEndedAt).isEqualTo(firstEndedAt);
    }

    @Test
    void endSession_sessionNotFound_404() throws Exception {
        mockMvc.perform(post("/sessions/999999/end"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }

    private Long createSessionViaApi() throws Exception {
        MvcResult result = mockMvc.perform(post("/sessions"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("sessionId").asLong();
    }

    private LocalDateTime parseTimestamp(String json, String field) throws Exception {
        return LocalDateTime.parse(objectMapper.readTree(json).get(field).asText());
    }
}
