package br.com.fiap.hospital.agendamento.infrastructure.config;

import static org.assertj.core.api.Assertions.*;
import br.com.fiap.hospital.agendamento.infrastructure.messaging.*;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

class ConfiguracaoDoRelayTest {
    @Test void schedulerEsperaConclusaoEDelegaAoBeanTransacional() throws Exception {
        var agenda=OutboxScheduler.class.getMethod("publicarPendentes").getAnnotation(Scheduled.class);
        assertThat(agenda.fixedDelay()).isEqualTo(1000);
        assertThat(agenda.fixedRate()).isEqualTo(-1);
        assertThat(OutboxRelay.class.getMethod("executar").getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(OutboxEventPublisher.class.getMethod("publicar",br.com.fiap.hospital.agendamento.domain.EventoDeConsulta.class)
            .getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(OutboxScheduler.class.getDeclaredConstructors()[0].getParameterTypes()).containsExactly(OutboxRelay.class);
    }
}
