package com.example.chat.realtime.pubsub;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

// Registers the pattern subscription after the container is built.
// Keeping this out of RedisConfig avoids circular dependency with SessionRegistry-backed subscriber.
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
