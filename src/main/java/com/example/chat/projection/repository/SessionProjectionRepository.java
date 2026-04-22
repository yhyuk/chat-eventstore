package com.example.chat.projection.repository;

import com.example.chat.projection.domain.SessionProjection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionProjectionRepository extends JpaRepository<SessionProjection, Long> {
}
