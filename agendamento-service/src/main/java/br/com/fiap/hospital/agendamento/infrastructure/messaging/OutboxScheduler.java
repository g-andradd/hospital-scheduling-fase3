package br.com.fiap.hospital.agendamento.infrastructure.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="hospital.outbox.scheduler-enabled",havingValue="true",matchIfMissing=true)
public class OutboxScheduler {
    private final OutboxRelay relay;
    public OutboxScheduler(OutboxRelay relay) { this.relay=relay; }
    @Scheduled(fixedDelay=1000)
    public void publicarPendentes() { relay.executar(); }
}

