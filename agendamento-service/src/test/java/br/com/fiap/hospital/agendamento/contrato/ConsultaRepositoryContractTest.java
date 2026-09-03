package br.com.fiap.hospital.agendamento.contrato;

import static br.com.fiap.hospital.agendamento.Cenario.AGORA;
import static br.com.fiap.hospital.agendamento.Cenario.daquiA;
import static br.com.fiap.hospital.agendamento.Cenario.periodo;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.agendamento.domain.Consulta;
import br.com.fiap.hospital.agendamento.domain.FiltroDeConsultas;
import br.com.fiap.hospital.agendamento.domain.PeriodoConsulta;
import br.com.fiap.hospital.agendamento.domain.StatusConsulta;
import br.com.fiap.hospital.agendamento.domain.port.ConsultaRepositoryPort;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Contrato de {@link ConsultaRepositoryPort}, escrito apenas contra a interface.
 *
 * <p>Duas subclasses o executam: uma com o fake em memoria, outra com o adaptador real
 * contra Postgres. Toda assercao roda nas duas implementacoes.
 *
 * <p>A razao e o risco que o design do M01 registrou nominalmente: fake e adaptador
 * divergirem e a divergencia so aparecer muito depois. Com uma suite so, o fake nao
 * pode ter sua propria nocao de sobreposicao enquanto o SQL faz outra coisa — divergir
 * sem ficar vermelho e impossivel.
 */
public abstract class ConsultaRepositoryContractTest {

    /** Implementacao sob teste, ja vazia. */
    protected abstract ConsultaRepositoryPort repositorio();

    /** Identificadores validos para as chaves estrangeiras, quando o banco as exigir. */
    protected abstract UUID pacienteId();

    protected abstract UUID medicoId();

    protected abstract UUID outroPacienteId();

    protected abstract UUID outroMedicoId();

    /** Quem registrou a consulta. No banco precisa ser um usuario existente. */
    protected abstract UUID registradoPorId();

    protected Consulta consulta(
            UUID pacienteId, UUID medicoId, OffsetDateTime inicio, StatusConsulta status) {
        return Consulta.reconstituir(
                UUID.randomUUID(), pacienteId, medicoId, registradoPorId(),
                periodo(inicio), status, null,
                status == StatusConsulta.CANCELADA ? "motivo" : null,
                AGORA, AGORA);
    }

    protected Consulta gravar(
            UUID pacienteId, UUID medicoId, OffsetDateTime inicio, StatusConsulta status) {
        return repositorio().salvar(consulta(pacienteId, medicoId, inicio, status));
    }

    @Nested
    @DisplayName("gravacao e recuperacao")
    class GravacaoERecuperacao {

        @Test
        @DisplayName("Scenario: Consulta registrada sobrevive ao reinicio")
        void consultaGravadaERecuperadaIntegralmente() {
            Consulta gravada = gravar(pacienteId(), medicoId(), daquiA(24), StatusConsulta.AGENDADA);

            Consulta lida = repositorio().buscarPorId(gravada.id()).orElseThrow();

            assertThat(lida.id()).isEqualTo(gravada.id());
            assertThat(lida.pacienteId()).isEqualTo(pacienteId());
            assertThat(lida.medicoId()).isEqualTo(medicoId());
            assertThat(lida.periodo().inicio()).isEqualTo(daquiA(24));
            assertThat(lida.periodo().duracaoMinutos()).isEqualTo(30);
            assertThat(lida.status()).isEqualTo(StatusConsulta.AGENDADA);
        }

        @Test
        @DisplayName("Scenario: Mudanca de estado e persistida")
        void mudancaDeEstadoEPersistida() {
            Consulta gravada = gravar(pacienteId(), medicoId(), daquiA(24), StatusConsulta.AGENDADA);

            Consulta carregada = repositorio().buscarPorId(gravada.id()).orElseThrow();
            carregada.cancelar("paciente desistiu", AGORA.plusHours(1));
            repositorio().salvar(carregada);

            Consulta relida = repositorio().buscarPorId(gravada.id()).orElseThrow();
            assertThat(relida.status()).isEqualTo(StatusConsulta.CANCELADA);
            assertThat(relida.motivoCancelamento()).isEqualTo("paciente desistiu");
        }

        @Test
        @DisplayName("Scenario: Operacao recusada nao deixa registro")
        void operacaoRecusadaNaoDeixaRegistro() {
            Consulta gravada = gravar(pacienteId(), medicoId(), daquiA(24), StatusConsulta.AGENDADA);

            // Muta o objeto de dominio e NAO chama salvar, simulando uma operacao que
            // foi recusada depois da mutacao. Nada pode chegar ao armazenamento.
            Consulta carregada = repositorio().buscarPorId(gravada.id()).orElseThrow();
            carregada.atualizar(periodo(daquiA(96)), null, "nao deveria persistir", AGORA);

            Consulta relida = repositorio().buscarPorId(gravada.id()).orElseThrow();
            assertThat(relida.periodo().inicio()).isEqualTo(daquiA(24));
            assertThat(relida.observacoes()).isNull();
        }

        @Test
        @DisplayName("Scenario: Consulta gravada no passado e recuperavel")
        void consultaNoPassadoERecuperavel() {
            Consulta antiga = gravar(
                    pacienteId(), medicoId(), AGORA.minusMonths(3), StatusConsulta.REALIZADA);

            Consulta lida = repositorio().buscarPorId(antiga.id()).orElseThrow();

            assertThat(lida.periodo().inicio()).isEqualTo(AGORA.minusMonths(3));
            assertThat(lida.status()).isEqualTo(StatusConsulta.REALIZADA);
        }

        @Test
        @DisplayName("identificador inexistente devolve vazio")
        void identificadorInexistenteDevolveVazio() {
            assertThat(repositorio().buscarPorId(UUID.randomUUID())).isEmpty();
        }
    }

    @Nested
    @DisplayName("deteccao de conflito")
    class DeteccaoDeConflito {

        @Test
        @DisplayName("Scenario: Conflito e detectado contra dados persistidos — medico")
        void conflitoDoMedico() {
            gravar(outroPacienteId(), medicoId(), daquiA(24), StatusConsulta.AGENDADA);

            assertThat(repositorio().buscarAtivasDoMedicoNoPeriodo(medicoId(), periodo(daquiA(24))))
                    .hasSize(1);
        }

        @Test
        @DisplayName("Scenario: Conflito e detectado contra dados persistidos — paciente")
        void conflitoDoPaciente() {
            gravar(pacienteId(), outroMedicoId(), daquiA(24), StatusConsulta.AGENDADA);

            assertThat(repositorio().buscarAtivasDoPacienteNoPeriodo(pacienteId(), periodo(daquiA(24))))
                    .hasSize(1);
        }

        @Test
        @DisplayName("Scenario: Periodos adjacentes persistidos nao sao conflito")
        void periodosAdjacentesNaoSaoConflito() {
            // A gravada ocupa [24h, 24h30). A buscada comeca exatamente as 24h30.
            gravar(outroPacienteId(), medicoId(), daquiA(24), StatusConsulta.AGENDADA);

            assertThat(repositorio().buscarAtivasDoMedicoNoPeriodo(
                            medicoId(), periodo(daquiA(24).plusMinutes(30))))
                    .as("consulta que comeca quando a outra termina nao conflita")
                    .isEmpty();
        }

        @Test
        @DisplayName("a borda simetrica tambem nao conflita")
        void bordaSimetricaNaoConflita() {
            gravar(outroPacienteId(), medicoId(), daquiA(24), StatusConsulta.AGENDADA);

            assertThat(repositorio().buscarAtivasDoMedicoNoPeriodo(
                            medicoId(), periodo(daquiA(24).minusMinutes(30))))
                    .isEmpty();
        }

        @Test
        @DisplayName("invasao de um minuto ja e conflito")
        void invasaoDeUmMinutoEConflito() {
            gravar(outroPacienteId(), medicoId(), daquiA(24), StatusConsulta.AGENDADA);

            assertThat(repositorio().buscarAtivasDoMedicoNoPeriodo(
                            medicoId(), periodo(daquiA(24).plusMinutes(29))))
                    .hasSize(1);
        }

        @ParameterizedTest
        @EnumSource(value = StatusConsulta.class, names = {"CANCELADA", "REALIZADA"})
        @DisplayName("Scenario: Consulta encerrada persistida nao bloqueia a agenda")
        void consultaEncerradaNaoBloqueia(StatusConsulta encerrada) {
            gravar(outroPacienteId(), medicoId(), daquiA(24), encerrada);

            assertThat(repositorio().buscarAtivasDoMedicoNoPeriodo(medicoId(), periodo(daquiA(24))))
                    .isEmpty();
        }

        @Test
        @DisplayName("Scenario: Busca de conflito e delimitada na origem")
        void buscaEDelimitadaNaOrigem() {
            for (int hora = 1; hora <= 20; hora++) {
                gravar(outroPacienteId(), medicoId(), daquiA(hora * 2L), StatusConsulta.AGENDADA);
            }
            PeriodoConsulta alvo = periodo(daquiA(8));

            var encontradas = repositorio().buscarAtivasDoMedicoNoPeriodo(medicoId(), alvo);

            assertThat(encontradas)
                    .as("so as que realmente se sobrepoem voltam do armazenamento")
                    .hasSize(1);
            assertThat(encontradas.getFirst().periodo().sobrepoe(alvo)).isTrue();
        }

        @Test
        @DisplayName("agenda de outro medico nao interfere")
        void agendaDeOutroMedicoNaoInterfere() {
            gravar(outroPacienteId(), outroMedicoId(), daquiA(24), StatusConsulta.AGENDADA);

            assertThat(repositorio().buscarAtivasDoMedicoNoPeriodo(medicoId(), periodo(daquiA(24))))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("listagem")
    class Listagem {

        @Test
        @DisplayName("sem filtros devolve tudo")
        void semFiltrosDevolveTudo() {
            gravar(pacienteId(), medicoId(), daquiA(24), StatusConsulta.AGENDADA);
            gravar(outroPacienteId(), outroMedicoId(), daquiA(48), StatusConsulta.CANCELADA);

            assertThat(repositorio().listar(FiltroDeConsultas.vazio())).hasSize(2);
        }

        @Test
        @DisplayName("filtra por paciente")
        void filtraPorPaciente() {
            Consulta minha = gravar(pacienteId(), medicoId(), daquiA(24), StatusConsulta.AGENDADA);
            gravar(outroPacienteId(), outroMedicoId(), daquiA(48), StatusConsulta.AGENDADA);

            assertThat(repositorio().listar(
                            new FiltroDeConsultas(pacienteId(), null, null, null, null)))
                    .extracting(Consulta::id)
                    .containsExactly(minha.id());
        }

        @Test
        @DisplayName("filtra por medico")
        void filtraPorMedico() {
            Consulta dele = gravar(pacienteId(), medicoId(), daquiA(24), StatusConsulta.AGENDADA);
            gravar(outroPacienteId(), outroMedicoId(), daquiA(48), StatusConsulta.AGENDADA);

            assertThat(repositorio().listar(
                            new FiltroDeConsultas(null, medicoId(), null, null, null)))
                    .extracting(Consulta::id)
                    .containsExactly(dele.id());
        }

        @Test
        @DisplayName("filtra por status")
        void filtraPorStatus() {
            gravar(pacienteId(), medicoId(), daquiA(24), StatusConsulta.AGENDADA);
            Consulta cancelada =
                    gravar(outroPacienteId(), outroMedicoId(), daquiA(48), StatusConsulta.CANCELADA);

            assertThat(repositorio().listar(new FiltroDeConsultas(
                            null, null, Set.of(StatusConsulta.CANCELADA), null, null)))
                    .extracting(Consulta::id)
                    .containsExactly(cancelada.id());
        }

        @Test
        @DisplayName("filtra por intervalo de datas")
        void filtraPorIntervalo() {
            Consulta proxima = gravar(pacienteId(), medicoId(), daquiA(24), StatusConsulta.AGENDADA);
            gravar(outroPacienteId(), outroMedicoId(), daquiA(240), StatusConsulta.AGENDADA);

            assertThat(repositorio().listar(
                            new FiltroDeConsultas(null, null, null, daquiA(1), daquiA(48))))
                    .extracting(Consulta::id)
                    .containsExactly(proxima.id());
        }

        @Test
        @DisplayName("combina criterios com E")
        void combinaCriteriosComE() {
            gravar(pacienteId(), medicoId(), daquiA(24), StatusConsulta.AGENDADA);

            assertThat(repositorio().listar(new FiltroDeConsultas(
                            pacienteId(), medicoId(), Set.of(StatusConsulta.CANCELADA), null, null)))
                    .isEmpty();
        }

        @Test
        @DisplayName("filtro sem resultado devolve lista vazia")
        void filtroSemResultado() {
            gravar(pacienteId(), medicoId(), daquiA(24), StatusConsulta.AGENDADA);

            assertThat(repositorio().listar(
                            new FiltroDeConsultas(UUID.randomUUID(), null, null, null, null)))
                    .isEmpty();
        }
    }
}
