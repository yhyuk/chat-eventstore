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
            // 참여자가 여럿인 세션에서 JOIN 대신 EXISTS를 사용해 카디널리티 폭발을 방지한다.
            // Participant는 session을 @ManyToOne으로 참조하므로 QueryDSL 경로는 session.id를 사용한다.
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
