package com.example.chat.realtime.pubsub;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

// 컨테이너 빌드 후 패턴 구독을 등록한다.
// RedisConfig에 두지 않는 이유: SessionRegistry 기반 subscriber와의 순환 의존성을 방지하기 위함.
@Component
@RequiredArgsConstructor
public class RedisSubscriptionRegistrar {

    private final RedisMessageListenerContainer container;
    private final RedisMessageSubscriber subscriber;

    @PostConstruct
    public void register() {
        container.addMessageListener(subscriber, new PatternTopic(RedisMessagePublisher.CHANNEL_PREFIX + "*"));
    }
}
