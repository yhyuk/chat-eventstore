package com.example.chat.projection.repository;

import com.example.chat.projection.domain.DeadLetterEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeadLetterEventRepository extends JpaRepository<DeadLetterEvent, Long> {
}
