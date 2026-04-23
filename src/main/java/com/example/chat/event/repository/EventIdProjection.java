package com.example.chat.event.repository;

// SKIP LOCKED 배치 조회용 인터페이스 프로젝션. 전체 Event 엔티티를 영속성 컨텍스트에 올리지 않아
// 아웃박스 파이프라인에서 더티 체킹 부작용이 발생하지 않는다.
public interface EventIdProjection {
    Long getSessionId();

    Long getSequence();
}
