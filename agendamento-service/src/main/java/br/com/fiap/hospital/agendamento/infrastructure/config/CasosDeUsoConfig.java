package br.com.fiap.hospital.agendamento.infrastructure.config;

import br.com.fiap.hospital.agendamento.application.AgendarConsultaUseCase;
import br.com.fiap.hospital.agendamento.application.AtualizarConsultaUseCase;
import br.com.fiap.hospital.agendamento.application.BuscarConsultaPorIdUseCase;
import br.com.fiap.hospital.agendamento.application.CancelarConsultaUseCase;
import br.com.fiap.hospital.agendamento.application.ConfirmarConsultaUseCase;
import br.com.fiap.hospital.agendamento.application.ListarConsultasUseCase;
import br.com.fiap.hospital.agendamento.domain.port.ConsultaRepositoryPort;
import br.com.fiap.hospital.agendamento.domain.port.EventPublisherPort;
import br.com.fiap.hospital.agendamento.infrastructure.messaging.EventPublisherLogAdapter;
import br.com.fiap.hospital.agendamento.domain.port.UsuarioRepositoryPort;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registra os casos de uso como beans.
 *
 * <p>A montagem acontece aqui, e nao por anotacao nas classes de {@code application},
 * para manter aquele pacote livre de framework — a mesma razao do decorador
 * transacional existir.
 */
@Configuration
public class CasosDeUsoConfig {

    /**
     * Relogio unico da aplicacao. Nenhuma regra chama {@code now()} sem ele, e e o que
     * permite congelar o tempo nos testes.
     */
    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("America/Sao_Paulo"));
    }

    /** Provisorio: substituido pelo publicador com outbox no M05. */
    @Bean
    public EventPublisherPort eventPublisherPort() {
        return new EventPublisherLogAdapter();
    }

    @Bean
    public AgendarConsultaUseCase agendarConsultaUseCase(
            ConsultaRepositoryPort consultas,
            UsuarioRepositoryPort usuarios,
            EventPublisherPort eventos,
            Clock clock) {
        return new AgendarConsultaUseCase(consultas, usuarios, eventos, clock);
    }

    @Bean
    public AtualizarConsultaUseCase atualizarConsultaUseCase(
            ConsultaRepositoryPort consultas,
            UsuarioRepositoryPort usuarios,
            EventPublisherPort eventos,
            Clock clock) {
        return new AtualizarConsultaUseCase(consultas, usuarios, eventos, clock);
    }

    @Bean
    public ConfirmarConsultaUseCase confirmarConsultaUseCase(
            ConsultaRepositoryPort consultas, EventPublisherPort eventos, Clock clock) {
        return new ConfirmarConsultaUseCase(consultas, eventos, clock);
    }

    @Bean
    public CancelarConsultaUseCase cancelarConsultaUseCase(
            ConsultaRepositoryPort consultas, EventPublisherPort eventos, Clock clock) {
        return new CancelarConsultaUseCase(consultas, eventos, clock);
    }

    @Bean
    public BuscarConsultaPorIdUseCase buscarConsultaPorIdUseCase(ConsultaRepositoryPort consultas) {
        return new BuscarConsultaPorIdUseCase(consultas);
    }

    @Bean
    public ListarConsultasUseCase listarConsultasUseCase(ConsultaRepositoryPort consultas) {
        return new ListarConsultasUseCase(consultas);
    }
}
