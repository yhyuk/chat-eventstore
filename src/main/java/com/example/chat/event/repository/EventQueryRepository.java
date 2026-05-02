package com.example.chat.event.repository;

import com.example.chat.event.domain.Event;

import java.util.List;
import java.util.Map;

// 아웃박스 폴러의 N+1 재조회를 제거하기 위한 QueryDSL 배치 조회 프래그먼트.
// EventRepository에 다중 인터페이스 상속으로 조합된다.
public interface EventQueryRepository {

    Map<Long, Event> fetchEventMapByIds(List<Long> ids);
}
