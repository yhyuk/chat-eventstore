package com.example.chat.event.repository;

// Interface projection for SKIP LOCKED batch fetch that avoids loading full Event entities
// into the persistence context. Keeps the outbox pipeline free of dirty-checking side effects.
public interface EventIdProjection {
    Long getSessionId();

    Long getSequence();
}
