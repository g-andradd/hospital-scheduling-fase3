package br.com.fiap.hospital.agendamento.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.agendamento.application.AgendarConsultaUseCase;
import br.com.fiap.hospital.agendamento.application.AtualizarConsultaUseCase;
import br.com.fiap.hospital.agendamento.application.BuscarConsultaPorIdUseCase;
import br.com.fiap.hospital.agendamento.application.CancelarConsultaUseCase;
import br.com.fiap.hospital.agendamento.application.ConfirmarConsultaUseCase;
import br.com.fiap.hospital.agendamento.application.ListarConsultasUseCase;
import br.com.fiap.hospital.agendamento.domain.Medico;
import br.com.fiap.hospital.agendamento.domain.Paciente;
import br.com.fiap.hospital.agendamento.domain.Usuario;
import br.com.fiap.hospital.agendamento.domain.port.ConsultaRepositoryPort;
import br.com.fiap.hospital.agendamento.domain.port.EventPublisherPort;
import br.com.fiap.hospital.agendamento.domain.port.UsuarioRepositoryPort;
import br.com.fiap.hospital.agendamento.fake.ConsultaRepositoryFake;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Fiacao dos casos de uso, sem banco e sem container.
 *
 * <p>Existe para preencher parte da lacuna deixada pela remocao do {@code contextLoads}
 * do M00: como o contexto deste servico agora depende de datasource, JPA e Flyway, a
 * verificacao do contexto completo passou a viver nos testes {@code *IT}, que so rodam
 * no {@code mvn verify}.
 *
 * <p>Este teste nao substitui aqueles. Ele cobre a quebra mais provavel e mais barata
 * de detectar — alguem alterar um construtor de caso de uso ou remover um bean de
 * {@link CasosDeUsoConfig} — e a pega ainda no {@code mvn test}. Quebra em datasource,
 * JPA ou Flyway continua aparecendo so no {@code verify}.
 */
@DisplayName("CasosDeUsoConfig")
class CasosDeUsoConfigTest {

    private final ApplicationContextRunner contexto = new ApplicationContextRunner()
            .withUserConfiguration(CasosDeUsoConfig.class)
            .withBean(ConsultaRepositoryPort.class, ConsultaRepositoryFake::new)
            .withBean(UsuarioRepositoryPort.class, UsuarioRepositoryVazio::new);

    @Test
    @DisplayName("todos os seis casos de uso sao resolvidos")
    void todosOsCasosDeUsoSaoResolvidos() {
        contexto.run(ctx -> assertThat(ctx)
                .hasNotFailed()
                .hasSingleBean(AgendarConsultaUseCase.class)
                .hasSingleBean(AtualizarConsultaUseCase.class)
                .hasSingleBean(ConfirmarConsultaUseCase.class)
                .hasSingleBean(CancelarConsultaUseCase.class)
                .hasSingleBean(BuscarConsultaPorIdUseCase.class)
                .hasSingleBean(ListarConsultasUseCase.class));
    }

    @Test
    @DisplayName("o relogio da aplicacao e registrado")
    void relogioERegistrado() {
        contexto.run(ctx -> assertThat(ctx).hasSingleBean(Clock.class));
    }

    @Test
    @DisplayName("a porta de eventos tem um adaptador registrado")
    void portaDeEventosTemAdaptador() {
        contexto.run(ctx -> assertThat(ctx).hasSingleBean(EventPublisherPort.class));
    }

    @Test
    @DisplayName("o contexto falha de forma explicita quando falta uma porta")
    void faltaDePortaFalhaExplicitamente() {
        new ApplicationContextRunner()
                .withUserConfiguration(CasosDeUsoConfig.class)
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    /** O contrato de {@link UsuarioRepositoryPort} nao importa aqui; so a fiacao. */
    private static final class UsuarioRepositoryVazio implements UsuarioRepositoryPort {
        @Override
        public Optional<Paciente> buscarPacientePorId(UUID id) {
            return Optional.empty();
        }

        @Override
        public Optional<Medico> buscarMedicoPorId(UUID id) {
            return Optional.empty();
        }

        @Override
        public Optional<Usuario> buscarUsuarioPorId(UUID id) {
            return Optional.empty();
        }
    }
}
