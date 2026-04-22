package com.example.chat.event.repository;

import com.example.chat.event.domain.Event;
import com.example.chat.event.domain.EventId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, EventId> {
}
