package br.com.fiap.hospital.notificacao;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class NotificacaoConfig {

    /**
     * Unica fonte de tempo do servico.
     *
     * <p>Convencao do projeto: nada le o relogio direto. Aqui isso tambem torna os
     * instantes registrados asseraveis por valor exato nos testes, em vez de "algo perto
     * de agora". A ordenacao dos fatos nao depende deste bean — ela usa o occurredAt do
     * envelope.
     */
    @Bean
    Clock clock() { return Clock.systemUTC(); }
}
