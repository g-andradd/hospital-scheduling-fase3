package br.com.fiap.hospital.agendamento.application;

import static br.com.fiap.hospital.agendamento.Cenario.AGORA;
import static br.com.fiap.hospital.agendamento.Cenario.consultaExistente;
import static br.com.fiap.hospital.agendamento.Cenario.daquiA;
import static br.com.fiap.hospital.agendamento.Cenario.haQuanto;
import static br.com.fiap.hospital.agendamento.Cenario.medico;
import static br.com.fiap.hospital.agendamento.Cenario.paciente;
import static br.com.fiap.hospital.agendamento.Cenario.relogioEm;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.fiap.hospital.agendamento.domain.Consulta;
import br.com.fiap.hospital.agendamento.domain.Medico;
import br.com.fiap.hospital.agendamento.domain.Paciente;
import br.com.fiap.hospital.agendamento.domain.StatusConsulta;
import br.com.fiap.hospital.agendamento.domain.TipoEventoConsulta;
import br.com.fiap.hospital.agendamento.domain.exception.AgendamentoNoPassadoException;
import br.com.fiap.hospital.agendamento.domain.exception.ConflitoDeAgendaException;
import br.com.fiap.hospital.agendamento.domain.exception.ConsultaNaoEncontradaException;
import br.com.fiap.hospital.agendamento.domain.exception.TransicaoDeStatusInvalidaException;
import br.com.fiap.hospital.agendamento.fake.ConsultaRepositoryFake;
import br.com.fiap.hospital.agendamento.fake.EventPublisherFake;
import br.com.fiap.hospital.agendamento.fake.UsuarioRepositoryFake;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("AtualizarConsultaUseCase — Requirement: Alteracao de consulta")
class AtualizarConsultaUseCaseTest {

    private static final String OBSERVACAO_ORIGINAL = "Paciente relatou dor toracica";

    /**
     * O relogio fica uma hora a frente do instante em que as consultas do cenario foram
     * criadas. Assim {@code atualizadoEm} muda numa alteracao bem-sucedida e permanece
     * em {@link br.com.fiap.hospital.agendamento.Cenario#AGORA} quando nada acontece —
     * sem essa diferenca, o campo nao distinguiria mutacao de nao-mutacao.
     */
    private static final OffsetDateTime QUANDO_ALTERA = AGORA.plusHours(1);

    private ConsultaRepositoryFake consultas;
    private UsuarioRepositoryFake usuarios;
    private EventPublisherFake eventos;
    private AtualizarConsultaUseCase useCase;

    private Paciente maria;
    private Medico joao;
    private Consulta consulta;

    @BeforeEach
    void preparar() {
        maria = paciente();
        joao = medico();
        consulta = comObservacoes(
                consultaExistente(maria, joao, daquiA(24), StatusConsulta.AGENDADA));
        consultas = new ConsultaRepositoryFake().com(consulta);
        usuarios = new UsuarioRepositoryFake().com(maria).com(joao);
        eventos = new EventPublisherFake();
        useCase = new AtualizarConsultaUseCase(consultas, usuarios, eventos, relogioEm(QUANDO_ALTERA));
    }

    private static Consulta comObservacoes(Consulta base) {
        return Consulta.reconstituir(
                base.id(), base.pacienteId(), base.medicoId(), base.registradoPorId(),
                base.periodo(), base.status(), OBSERVACAO_ORIGINAL, base.motivoCancelamento(),
                base.criadoEm(), base.atualizadoEm());
    }

    /**
     * Um caminho negativo nao pode deixar rastro: nem no agregado, nem no publicador.
     *
     * <p>Conferir {@code atualizadoEm} e o que pega mutacao ocorrida antes de a
     * validacao lancar — que sob JPA viraria escrita real no flush da transacao.
     */
    private void assertConsultaIntacta(Consulta antes) {
        Consulta depois = consultas.buscarPorId(antes.id()).orElseThrow();
        SoftAssertions.assertSoftly(macio -> {
            macio.assertThat(depois.periodo()).as("periodo").isEqualTo(antes.periodo());
            macio.assertThat(depois.medicoId()).as("medico").isEqualTo(antes.medicoId());
            macio.assertThat(depois.observacoes()).as("observacoes").isEqualTo(OBSERVACAO_ORIGINAL);
            macio.assertThat(depois.status()).as("status").isEqualTo(antes.status());
            macio.assertThat(depois.atualizadoEm()).as("atualizadoEm").isEqualTo(AGORA);
            macio.assertThat(eventos.nadaPublicado()).as("nenhum evento publicado").isTrue();
        });
    }

    @Test
    @DisplayName("Scenario: Remarcacao bem-sucedida — novo periodo, status inalterado")
    void remarcacaoBemSucedida() {
        ConsultaResumo resumo = useCase.executar(new AtualizarConsultaCommand(
                consulta.id(), daquiA(48), null, null, "remarcado a pedido"));

        assertThat(resumo.dataHora()).isEqualTo(daquiA(48));
        assertThat(resumo.status()).isEqualTo(StatusConsulta.AGENDADA);
        assertThat(resumo.observacoes()).isEqualTo("remarcado a pedido");
        assertThat(resumo.atualizadoEm()).isEqualTo(QUANDO_ALTERA);
        assertThat(eventos.tipos()).containsExactly(TipoEventoConsulta.ATUALIZADA);
    }

    @Test
    @DisplayName("alteracao so de horario preserva as observacoes existentes")
    void alteracaoSoDeHorarioPreservaObservacoes() {
        ConsultaResumo resumo = useCase.executar(new AtualizarConsultaCommand(
                consulta.id(), daquiA(48), null, null, null));

        assertThat(resumo.dataHora()).isEqualTo(daquiA(48));
        assertThat(resumo.observacoes())
                .as("observacao clinica nao pode sumir numa remarcacao")
                .isEqualTo(OBSERVACAO_ORIGINAL);
    }

    @Test
    @DisplayName("alteracao so de medico preserva periodo e observacoes")
    void alteracaoSoDeMedicoPreservaORestante() {
        Medico ana = medico("Dra. Ana Reis", "ana.reis@hospital.com", "SP-54321");
        usuarios.com(ana);

        ConsultaResumo resumo = useCase.executar(new AtualizarConsultaCommand(
                consulta.id(), null, null, ana.id(), null));

        assertThat(resumo.medicoId()).isEqualTo(ana.id());
        assertThat(resumo.dataHora()).isEqualTo(daquiA(24));
        assertThat(resumo.observacoes()).isEqualTo(OBSERVACAO_ORIGINAL);
    }

    @Test
    @DisplayName("Scenario: A propria consulta nao conflita consigo mesma")
    void aPropriaConsultaNaoConflitaConsigoMesma() {
        ConsultaResumo resumo = useCase.executar(new AtualizarConsultaCommand(
                consulta.id(), null, null, null, "so trocando a observacao"));

        assertThat(resumo.observacoes()).isEqualTo("so trocando a observacao");
        assertThat(resumo.dataHora()).isEqualTo(daquiA(24));
    }

    @Test
    @DisplayName("observacao em branco apaga deliberadamente, nulo nao")
    void observacaoEmBrancoApagaDeliberadamente() {
        assertThat(useCase.executar(new AtualizarConsultaCommand(
                        consulta.id(), null, null, null, "   ")).observacoes())
                .isNull();
    }

    @Test
    @DisplayName("Scenario: Remarcacao com conflito e recusada — consulta intacta")
    void remarcacaoComConflitoERecusada() {
        Paciente outro = paciente("Jose Silva", "jose@hospital.com", "111.444.777-35");
        consultas.com(consultaExistente(outro, joao, daquiA(48), StatusConsulta.AGENDADA));

        assertThatThrownBy(() -> useCase.executar(new AtualizarConsultaCommand(
                        consulta.id(), daquiA(48), null, null, "tentativa recusada")))
                .isInstanceOf(ConflitoDeAgendaException.class);

        assertConsultaIntacta(consulta);
    }

    @Test
    @DisplayName("Scenario: Remarcacao para o passado e recusada — consulta intacta")
    void remarcacaoParaOPassadoERecusada() {
        assertThatThrownBy(() -> useCase.executar(new AtualizarConsultaCommand(
                        consulta.id(), haQuanto(2), null, null, "tentativa recusada")))
                .isInstanceOf(AgendamentoNoPassadoException.class);

        assertConsultaIntacta(consulta);
    }

    @ParameterizedTest
    @EnumSource(value = StatusConsulta.class, names = {"REALIZADA", "CANCELADA"})
    @DisplayName("Scenario: Alteracao de consulta em status terminal e recusada — consulta intacta")
    void alteracaoDeConsultaTerminalERecusada(StatusConsulta terminal) {
        Consulta terminada =
                comObservacoes(consultaExistente(maria, joao, daquiA(72), terminal));
        consultas.com(terminada);

        assertThatThrownBy(() -> useCase.executar(new AtualizarConsultaCommand(
                        terminada.id(), daquiA(96), null, null, "tentativa recusada")))
                .isInstanceOf(TransicaoDeStatusInvalidaException.class);

        assertConsultaIntacta(terminada);
    }

    @Test
    @DisplayName("status terminal prevalece sobre conflito de agenda")
    void statusTerminalPrevaleceSobreConflito() {
        Consulta cancelada =
                comObservacoes(consultaExistente(maria, joao, daquiA(72), StatusConsulta.CANCELADA));
        Paciente outro = paciente("Jose Silva", "jose@hospital.com", "111.444.777-35");
        consultas.com(cancelada, consultaExistente(outro, joao, daquiA(96), StatusConsulta.AGENDADA));

        assertThatThrownBy(() -> useCase.executar(new AtualizarConsultaCommand(
                        cancelada.id(), daquiA(96), null, null, null)))
                .as("a recusa mais fundamental vem primeiro")
                .isInstanceOf(TransicaoDeStatusInvalidaException.class);
    }

    @Test
    @DisplayName("periodo no passado prevalece sobre conflito de agenda")
    void passadoPrevaleceSobreConflito() {
        Paciente outro = paciente("Jose Silva", "jose@hospital.com", "111.444.777-35");
        consultas.com(consultaExistente(outro, joao, haQuanto(2), StatusConsulta.AGENDADA));

        assertThatThrownBy(() -> useCase.executar(new AtualizarConsultaCommand(
                        consulta.id(), haQuanto(2), null, null, null)))
                .isInstanceOf(AgendamentoNoPassadoException.class);
    }

    @Test
    @DisplayName("Scenario: Alteracao de consulta inexistente e recusada")
    void alteracaoDeConsultaInexistenteERecusada() {
        assertThatThrownBy(() -> useCase.executar(new AtualizarConsultaCommand(
                        UUID.randomUUID(), daquiA(48), null, null, null)))
                .isInstanceOf(ConsultaNaoEncontradaException.class);

        assertThat(eventos.nadaPublicado()).isTrue();
    }

    @Test
    @DisplayName("medico inexistente e recusado e nao deixa rastro")
    void medicoInexistenteERecusado() {
        assertThatThrownBy(() -> useCase.executar(new AtualizarConsultaCommand(
                        consulta.id(), daquiA(48), null, UUID.randomUUID(), "tentativa recusada")))
                .isInstanceOf(br.com.fiap.hospital.agendamento.domain.exception
                        .MedicoNaoEncontradoException.class);

        assertConsultaIntacta(consulta);
    }

    @Test
    @DisplayName("conflito na agenda do novo medico e recusado sem trocar o medico")
    void conflitoNaAgendaDoNovoMedicoERecusado() {
        Medico ana = medico("Dra. Ana Reis", "ana.reis@hospital.com", "SP-54321");
        usuarios.com(ana);
        Paciente outro = paciente("Jose Silva", "jose@hospital.com", "111.444.777-35");
        consultas.com(consultaExistente(outro, ana, daquiA(24), StatusConsulta.AGENDADA));

        assertThatThrownBy(() -> useCase.executar(new AtualizarConsultaCommand(
                        consulta.id(), null, null, ana.id(), null)))
                .as("o conflito e avaliado contra o medico proposto, nao contra o atual")
                .isInstanceOf(ConflitoDeAgendaException.class);

        assertConsultaIntacta(consulta);
    }

    @Test
    @DisplayName("consulta encerrada nao bloqueia a remarcacao")
    void consultaEncerradaNaoBloqueiaRemarcacao() {
        Paciente outro = paciente("Jose Silva", "jose@hospital.com", "111.444.777-35");
        consultas.com(consultaExistente(outro, joao, daquiA(48), StatusConsulta.CANCELADA));

        assertThat(useCase.executar(new AtualizarConsultaCommand(
                        consulta.id(), daquiA(48), null, null, null)).dataHora())
                .isEqualTo(daquiA(48));
    }

    @Test
    @DisplayName("alteracao so de duracao mantem o horario de inicio")
    void alteracaoSoDeDuracao() {
        ConsultaResumo resumo = useCase.executar(new AtualizarConsultaCommand(
                consulta.id(), null, 60, null, null));

        assertThat(resumo.dataHora()).isEqualTo(daquiA(24));
        assertThat(resumo.duracaoMinutos()).isEqualTo(60);
        assertThat(resumo.observacoes()).isEqualTo(OBSERVACAO_ORIGINAL);
    }
}
