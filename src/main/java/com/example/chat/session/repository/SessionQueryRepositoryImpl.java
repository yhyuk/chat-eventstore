package com.example.chat.session.repository;

import com.example.chat.session.domain.QParticipant;
import com.example.chat.session.domain.QSession;
import com.example.chat.session.domain.SessionStatus;
import com.example.chat.session.dto.SessionListResponse;
import com.example.chat.session.dto.SessionSummary;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@RequiredArgsConstructor
public class SessionQueryRepositoryImpl implements SessionQueryRepository {

    private final JPAQueryFactory queryFactory;

    @Override
    public SessionListResponse search(SessionStatus status,
                                      LocalDateTime from,
                                      LocalDateTime to,
                                      String participant,
                                      int page,
                                      int size) {
        QSession qSession = QSession.session;
        QParticipant qParticipant = QParticipant.participant;

        BooleanBuilder where = new BooleanBuilder();
        if (status != null) {
            where.and(qSession.status.eq(status));
        }
        if (from != null) {
            where.and(qSession.createdAt.goe(from));
        }
        if (to != null) {
            where.and(qSession.createdAt.lt(to));
        }
        if (participant != null) {
            // EXISTS avoids cardinality explosion vs JOIN when a session has multiple participants.
            // Participant maps session via @ManyToOne so the QueryDSL path is session.id, not sessionId.
            where.and(JPAExpressions.selectOne()
                    .from(qParticipant)
                    .where(qParticipant.session.id.eq(qSession.id)
                            .and(qParticipant.userId.eq(participant)))
                    .exists());
        }

        List<SessionSummary> content = queryFactory
                .select(Projections.constructor(SessionSummary.class,
                        qSession.id,
                        qSession.status,
                        qSession.createdAt,
                        qSession.endedAt,
                        qSession.lastSequence))
                .from(qSession)
                .where(where)
                .orderBy(qSession.id.desc())
                .offset((long) page * size)
                .limit(size)
                .fetch();

        Long total = queryFactory
                .select(qSession.count())
                .from(qSession)
                .where(where)
                .fetchOne();

        return SessionListResponse.of(content, total == null ? 0L : total, page, size);
    }
}
