package io.github.imzmq.interview.chat.application.memory;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ChatMemoryConfiguration {

    @Bean
    ChatMemoryCandidateValidator chatMemoryCandidateValidator() {
        return new ChatMemoryCandidateValidator();
    }

    @Bean
    Clock chatMemoryClock() {
        return Clock.systemUTC();
    }
}
