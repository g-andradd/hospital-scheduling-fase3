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
import br.com.fiap.hospital.agendamento.domain.port.VerificadorDeSenhaPort;
import br.com.fiap.hospital.agendamento.domain.port.EventPublisherPort;
import br.com.fiap.hospital.agendamento.domain.port.UsuarioRepositoryPort;
import br.com.fiap.hospital.agendamento.fake.ConsultaRepositoryFake;
import br.com.fiap.hospital.agendamento.application.AutenticarUsuarioUseCase;
import br.com.fiap.hospital.agendamento.infrastructure.transacao.AgendarConsultaUseCaseTransacional;
import br.com.fiap.hospital.agendamento.infrastructure.transacao.AutenticarUsuarioUseCaseTransacional;
import br.com.fiap.hospital.agendamento.infrastructure.transacao.AtualizarConsultaUseCaseTransacional;
import br.com.fiap.hospital.agendamento.infrastructure.transacao.BuscarConsultaPorIdUseCaseTransacional;
import br.com.fiap.hospital.agendamento.infrastructure.transacao.CancelarConsultaUseCaseTransacional;
import br.com.fiap.hospital.agendamento.infrastructure.transacao.ConfirmarConsultaUseCaseTransacional;
import br.com.fiap.hospital.agendamento.infrastructure.transacao.ListarConsultasUseCaseTransacional;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Fiacao dos casos de uso, sem banco e sem container.
 *
 * <p>Cobre a lacuna do {@code contextLoads} removido no M02 para a parte mais provavel
 * de quebrar — construtor de caso de uso alterado ou bean removido —, e a pega ainda no
 * {@code mvn test}. Quebra em datasource, JPA ou Flyway continua aparecendo so no
 * {@code mvn verify}.
 *
 * <p>Verifica tambem a garantia do M03: os casos de uso NUS nao sao beans. Se voltarem a
 * ser, um controller pode injeta-los por engano e a operacao roda sem transacao, o que
 * quebraria o outbox do M05 em silencio.
 */
@DisplayName("CasosDeUsoConfig")
class CasosDeUsoConfigTest {

    private final ApplicationContextRunner contexto = new ApplicationContextRunner()
            .withUserConfiguration(CasosDeUsoConfig.class)
            .withBean(ConsultaRepositoryPort.class, ConsultaRepositoryFake::new)
            .withBean(UsuarioRepositoryPort.class, UsuarioRepositoryVazio::new)
            .withBean(VerificadorDeSenhaPort.class, VerificadorDeSenhaFalso::new);

    @Test
    @DisplayName("todos os decoradores transacionais sao resolvidos")
    void osSeisDecoradoresSaoResolvidos() {
        contexto.run(ctx -> assertThat(ctx)
                .hasNotFailed()
                .hasSingleBean(AgendarConsultaUseCaseTransacional.class)
                .hasSingleBean(AtualizarConsultaUseCaseTransacional.class)
                .hasSingleBean(ConfirmarConsultaUseCaseTransacional.class)
                .hasSingleBean(CancelarConsultaUseCaseTransacional.class)
                .hasSingleBean(BuscarConsultaPorIdUseCaseTransacional.class)
                .hasSingleBean(ListarConsultasUseCaseTransacional.class)
                .hasSingleBean(AutenticarUsuarioUseCaseTransacional.class));
    }

    @Test
    @DisplayName("nenhum caso de uso nu esta registrado como bean")
    void nenhumCasoDeUsoNuERegistrado() {
        List<Class<?>> nus = List.of(
                AgendarConsultaUseCase.class,
                AtualizarConsultaUseCase.class,
                ConfirmarConsultaUseCase.class,
                CancelarConsultaUseCase.class,
                BuscarConsultaPorIdUseCase.class,
                ListarConsultasUseCase.class,
                AutenticarUsuarioUseCase.class);

        contexto.run(ctx -> {
            for (Class<?> nu : nus) {
                assertThat(ctx)
                        .as("%s nao pode ser injetavel: sem o decorador, a operacao roda "
                                + "fora de transacao e o outbox do M05 quebra em silencio",
                                nu.getSimpleName())
                        .doesNotHaveBean(nu);
            }
        });
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

    /** So a fiacao importa aqui; o algoritmo de senha tem teste proprio. */
    private static final class VerificadorDeSenhaFalso implements VerificadorDeSenhaPort {
        @Override
        public boolean confere(String senhaEmClaro, String hash) {
            return false;
        }

        @Override
        public void consumirTempoDeVerificacao() {
            // sem efeito
        }
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

        @Override
        public Optional<Usuario> buscarUsuarioPorEmail(String email) {
            return Optional.empty();
        }

        @Override
        public Optional<Paciente> buscarPacientePorUsuario(UUID usuarioId) {
            return Optional.empty();
        }

        @Override
        public Optional<Medico> buscarMedicoPorUsuario(UUID usuarioId) {
            return Optional.empty();
        }
    }
}
