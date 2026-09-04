package br.com.fiap.hospital.agendamento.application;

import static br.com.fiap.hospital.agendamento.Cenario.AGORA;
import static br.com.fiap.hospital.agendamento.Cenario.consultaExistente;
import static br.com.fiap.hospital.agendamento.Cenario.daquiA;
import static br.com.fiap.hospital.agendamento.Cenario.medico;
import static br.com.fiap.hospital.agendamento.Cenario.paciente;
import static br.com.fiap.hospital.agendamento.Cenario.relogioEm;
import static br.com.fiap.hospital.agendamento.Cenario.solicitanteMedico;
import static br.com.fiap.hospital.agendamento.Cenario.solicitantePaciente;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.fiap.hospital.agendamento.domain.Consulta;
import br.com.fiap.hospital.agendamento.domain.Medico;
import br.com.fiap.hospital.agendamento.domain.Paciente;
import br.com.fiap.hospital.agendamento.domain.PerfilUsuario;
import br.com.fiap.hospital.agendamento.domain.SolicitanteAutenticado;
import br.com.fiap.hospital.agendamento.domain.StatusConsulta;
import br.com.fiap.hospital.agendamento.domain.exception.AcessoNegadoException;
import br.com.fiap.hospital.agendamento.fake.ConsultaRepositoryFake;
import br.com.fiap.hospital.agendamento.fake.EventPublisherFake;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Requirement: o paciente so alcanca a propria consulta.
 *
 * <p>Estes testes vivem no {@code application}, e nao na camada web, de proposito. O
 * controller REST e a unica porta <b>hoje</b>; quando o historico consumir os mesmos
 * casos de uso por GraphQL, uma regra escrita no controller nao vai junto — e a falha
 * seria silenciosa: tudo compila, tudo passa, e o paciente le a consulta de outro.
 */
@DisplayName("Requirement: Regra de propriedade do paciente")
class PropriedadeDaConsultaUseCaseTest {

    private ConsultaRepositoryFake consultas;
    private BuscarConsultaPorIdUseCase buscar;
    private ConfirmarConsultaUseCase confirmar;
    private ListarConsultasUseCase listar;

    private Paciente maria;
    private Paciente jose;
    private Medico joao;

    private Consulta deMaria;
    private Consulta deJose;

    @BeforeEach
    void preparar() {
        maria = paciente("Maria Souza", "maria@hospital.com", "529.982.247-25");
        jose = paciente("Jose Silva", "jose@hospital.com", "111.444.777-35");
        joao = medico();

        deMaria = consultaExistente(maria, joao, daquiA(24), StatusConsulta.AGENDADA);
        deJose = consultaExistente(jose, joao, daquiA(48), StatusConsulta.AGENDADA);

        consultas = new ConsultaRepositoryFake().com(deMaria).com(deJose);
        buscar = new BuscarConsultaPorIdUseCase(consultas);
        confirmar = new ConfirmarConsultaUseCase(
                consultas, new EventPublisherFake(), relogioEm(AGORA.plusHours(1)));
        listar = new ListarConsultasUseCase(consultas);
    }

    @Nested
    @DisplayName("recuperacao")
    class Recuperacao {

        @Test
        @DisplayName("Scenario: Paciente nao recupera consulta de terceiro")
        void pacienteNaoRecuperaConsultaDeTerceiro() {
            assertThatThrownBy(() -> buscar.executar(deJose.id(), solicitantePaciente(maria)))
                    .isInstanceOf(AcessoNegadoException.class);
        }

        @Test
        @DisplayName("o paciente recupera a propria consulta")
        void pacienteRecuperaAPropria() {
            assertThat(buscar.executar(deMaria.id(), solicitantePaciente(maria)).id())
                    .isEqualTo(deMaria.id());
        }

        @Test
        @DisplayName("medico e enfermeiro recuperam qualquer consulta")
        void perfisClinicosRecuperamQualquer() {
            assertThatCode(() -> buscar.executar(deJose.id(), solicitanteMedico()))
                    .doesNotThrowAnyException();
        }

        /**
         * Um paciente sem identificador nao e titular de nada.
         *
         * <p>Acontece com token cujo {@code pacienteId} veio ilegivel. O caminho seguro e
         * negar: tratar ausencia como "pode tudo" inverteria a regra exatamente no caso
         * em que a identidade e duvidosa.
         */
        @Test
        @DisplayName("paciente sem identificador nao alcanca consulta alguma")
        void pacienteSemIdentificadorNaoAlcancaNada() {
            SolicitanteAutenticado semIdentificador = new SolicitanteAutenticado(
                    UUID.randomUUID(), PerfilUsuario.PACIENTE, null);

            assertThatThrownBy(() -> buscar.executar(deMaria.id(), semIdentificador))
                    .isInstanceOf(AcessoNegadoException.class);
        }
    }

    @Nested
    @DisplayName("confirmacao")
    class Confirmacao {

        @Test
        @DisplayName("Scenario: Paciente nao confirma consulta de terceiro")
        void pacienteNaoConfirmaConsultaDeTerceiro() {
            assertThatThrownBy(() -> confirmar.executar(deJose.id(), solicitantePaciente(maria)))
                    .isInstanceOf(AcessoNegadoException.class);

            assertThat(consultas.buscarPorId(deJose.id()).orElseThrow().status())
                    .as("a recusa acontece antes de qualquer mutacao")
                    .isEqualTo(StatusConsulta.AGENDADA);
        }

        @Test
        @DisplayName("Scenario: PACIENTE confirma a propria consulta")
        void pacienteConfirmaAPropria() {
            ConsultaResumo resumo = confirmar.executar(deMaria.id(), solicitantePaciente(maria));

            assertThat(resumo.status()).isEqualTo(StatusConsulta.CONFIRMADA);
        }
    }

    @Nested
    @DisplayName("listagem")
    class Listagem {

        /**
         * O identificador que o paciente envia e descartado, e nao validado.
         *
         * <p>A diferenca importa: recusar com 403 confirmaria que o outro paciente
         * existe. Substituir simplesmente devolve a listagem dele proprio.
         */
        @Test
        @DisplayName("Scenario: Filtro de paciente e forcado a propria identidade")
        void filtroDePacienteEForcado() {
            ListarConsultasQuery pedindoDeOutro = ListarConsultasQuery.filtrando(
                    jose.id(), null, null, null, null);

            var pagina = listar.executar(pedindoDeOutro, solicitantePaciente(maria));

            assertThat(pagina.conteudo())
                    .as("o pacienteId enviado e substituido, nunca honrado; o hasSize evita "
                            + "que uma lista vazia satisfaca a assercao sem provar nada")
                    .hasSize(1)
                    .allSatisfy(c -> assertThat(c.pacienteId()).isEqualTo(maria.id()));
        }

        @Test
        @DisplayName("Scenario: Listagem sem filtro tambem e recortada — Listagem recortada pela identidade do paciente")
        void listagemSemFiltroTambemERecortada() {
            var pagina = listar.executar(ListarConsultasQuery.semFiltro(),
                    solicitantePaciente(maria));

            assertThat(pagina.conteudo())
                    .hasSize(1)
                    .allSatisfy(c -> assertThat(c.pacienteId()).isEqualTo(maria.id()));
        }

        @Test
        @DisplayName("query nula tambem e recortada — o caminho sem filtro nenhum")
        void queryNulaTambemERecortada() {
            var pagina = listar.executar(null, solicitantePaciente(maria));

            assertThat(pagina.conteudo())
                    .as("o atalho da query nula nao pode escapar do recorte")
                    .hasSize(1)
                    .allSatisfy(c -> assertThat(c.pacienteId()).isEqualTo(maria.id()));
        }

        @Test
        @DisplayName("medico e enfermeiro listam sem recorte")
        void perfisClinicosListamSemRecorte() {
            assertThat(listar.executar(ListarConsultasQuery.semFiltro(), solicitanteMedico())
                    .conteudo())
                    .hasSize(2);
        }
    }

    /**
     * Scenario: Operacao nova sem a regra de propriedade e recusada.
     *
     * <p>O mecanismo que sustenta isso nao e um teste, e a assinatura: os casos de uso
     * que expoem dados de consulta exigem {@link SolicitanteAutenticado} como parametro.
     * Esquecer o solicitante nao produz uma operacao permissiva — produz um erro de
     * compilacao.
     *
     * <p>Este teste registra a garantia por reflexao, para que remover o parametro de
     * algum deles quebre aqui com a razao escrita, em vez de virar uma brecha silenciosa.
     */
    @Test
    @DisplayName("Scenario: Operacao nova sem a regra de propriedade e recusada")
    void casosDeUsoDeLeituraExigemSolicitante() {
        for (Class<?> casoDeUso : java.util.List.of(
                BuscarConsultaPorIdUseCase.class,
                ConfirmarConsultaUseCase.class,
                ListarConsultasUseCase.class)) {

            var executar = java.util.Arrays.stream(casoDeUso.getDeclaredMethods())
                    .filter(m -> m.getName().equals("executar"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(casoDeUso + " sem metodo executar"));

            assertThat(executar.getParameterTypes())
                    .as("%s precisa receber o solicitante: sem ele, a operacao roda sem "
                            + "dono e a regra de propriedade nao tem como ser aplicada",
                            casoDeUso.getSimpleName())
                    .contains(SolicitanteAutenticado.class);
        }
    }
}
