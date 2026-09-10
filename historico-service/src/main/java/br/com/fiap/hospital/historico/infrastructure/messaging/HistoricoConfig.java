package br.com.fiap.hospital.historico.infrastructure.messaging;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class HistoricoConfig {
    @Bean
    Clock clock() { return Clock.systemUTC(); }
}
