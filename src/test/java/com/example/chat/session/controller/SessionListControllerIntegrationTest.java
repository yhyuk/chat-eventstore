package com.example.chat.session.controller;

import com.example.chat.common.AbstractFullIntegrationTest;
import com.example.chat.session.domain.Participant;
import com.example.chat.session.domain.Session;
import com.example.chat.session.dto.SessionListResponse;
import com.example.chat.session.repository.ParticipantRepository;
import com.example.chat.session.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SessionListControllerIntegrationTest extends AbstractFullIntegrationTest {

    @LocalServerPort private int port;

    @Autowired private SessionRepository sessionRepository;
    @Autowired private ParticipantRepository participantRepository;

    private final RestTemplate rest = new RestTemplate();

    @BeforeEach
    void setUp() {
        participantRepository.deleteAllInBatch();
        sessionRepository.deleteAllInBatch();
    }

    @Test
    void list_without_filters_returns_all_sessions_paginated() {
        for (int i = 0; i < 3; i++) {
            sessionRepository.saveAndFlush(Session.create());
        }

        ResponseEntity<SessionListResponse> r = rest.getForEntity(
                url("/sessions"), SessionListResponse.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().total()).isEqualTo(3);
        assertThat(r.getBody().content()).hasSize(3);
    }

    @Test
    void list_with_participant_filter_uses_exists_subquery() {
        Session s = sessionRepository.saveAndFlush(Session.create());
        participantRepository.saveAndFlush(Participant.builder()
                .session(s).userId("alice").joinedAt(LocalDateTime.now()).build());
        sessionRepository.saveAndFlush(Session.create()); // no participants

        ResponseEntity<SessionListResponse> r = rest.getForEntity(
                url("/sessions?participant=alice"), SessionListResponse.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().total()).isEqualTo(1);
        assertThat(r.getBody().content().get(0).id()).isEqualTo(s.getId());
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
