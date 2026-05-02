package com.example.chat.event.repository;

import com.example.chat.event.domain.Event;
import com.example.chat.event.domain.QEvent;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class EventQueryRepositoryImpl implements EventQueryRepository {

    private final JPAQueryFactory queryFactory;

    @Override
    @Transactional(readOnly = true)
    public Map<Long, Event> fetchEventMapByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return queryFactory
                .selectFrom(QEvent.event)
                .where(QEvent.event.id.in(ids))
                .fetch()
                .stream()
                .collect(Collectors.toMap(Event::getId, Function.identity()));
    }
}
