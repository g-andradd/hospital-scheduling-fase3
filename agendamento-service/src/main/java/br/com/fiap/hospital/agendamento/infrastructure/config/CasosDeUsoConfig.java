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
import br.com.fiap.hospital.agendamento.infrastructure.transacao.AgendarConsultaUseCaseTransacional;
import br.com.fiap.hospital.agendamento.infrastructure.transacao.AtualizarConsultaUseCaseTransacional;
import br.com.fiap.hospital.agendamento.infrastructure.transacao.BuscarConsultaPorIdUseCaseTransacional;
import br.com.fiap.hospital.agendamento.infrastructure.transacao.CancelarConsultaUseCaseTransacional;
import br.com.fiap.hospital.agendamento.infrastructure.transacao.ConfirmarConsultaUseCaseTransacional;
import br.com.fiap.hospital.agendamento.infrastructure.transacao.ListarConsultasUseCaseTransacional;
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

    // Os casos de uso NUS nao sao beans. Cada um e construido aqui dentro e entregue ao
    // seu decorador, entao nao existe tipo injetavel sem transacao. Injetar por engano
    // deixa de ser possivel: falha na subida do contexto, e nao em silencio.

    @Bean
    public AgendarConsultaUseCaseTransacional agendarConsulta(
            ConsultaRepositoryPort consultas,
            UsuarioRepositoryPort usuarios,
            EventPublisherPort eventos,
            Clock clock) {
        return new AgendarConsultaUseCaseTransacional(
                new AgendarConsultaUseCase(consultas, usuarios, eventos, clock));
    }

    @Bean
    public AtualizarConsultaUseCaseTransacional atualizarConsulta(
            ConsultaRepositoryPort consultas,
            UsuarioRepositoryPort usuarios,
            EventPublisherPort eventos,
            Clock clock) {
        return new AtualizarConsultaUseCaseTransacional(
                new AtualizarConsultaUseCase(consultas, usuarios, eventos, clock));
    }

    @Bean
    public ConfirmarConsultaUseCaseTransacional confirmarConsulta(
            ConsultaRepositoryPort consultas, EventPublisherPort eventos, Clock clock) {
        return new ConfirmarConsultaUseCaseTransacional(
                new ConfirmarConsultaUseCase(consultas, eventos, clock));
    }

    @Bean
    public CancelarConsultaUseCaseTransacional cancelarConsulta(
            ConsultaRepositoryPort consultas, EventPublisherPort eventos, Clock clock) {
        return new CancelarConsultaUseCaseTransacional(
                new CancelarConsultaUseCase(consultas, eventos, clock));
    }

    @Bean
    public BuscarConsultaPorIdUseCaseTransacional buscarConsultaPorId(
            ConsultaRepositoryPort consultas) {
        return new BuscarConsultaPorIdUseCaseTransacional(
                new BuscarConsultaPorIdUseCase(consultas));
    }

    @Bean
    public ListarConsultasUseCaseTransacional listarConsultas(ConsultaRepositoryPort consultas) {
        return new ListarConsultasUseCaseTransacional(new ListarConsultasUseCase(consultas));
    }
}
