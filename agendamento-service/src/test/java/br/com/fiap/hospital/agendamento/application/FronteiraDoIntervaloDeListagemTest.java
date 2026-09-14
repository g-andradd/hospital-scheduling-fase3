package br.com.fiap.hospital.agendamento.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.fiap.hospital.agendamento.domain.Consulta;
import br.com.fiap.hospital.agendamento.domain.FaixaTemporalSuportada;
import br.com.fiap.hospital.agendamento.domain.FiltroDeConsultas;
import br.com.fiap.hospital.agendamento.domain.Pagina;
import br.com.fiap.hospital.agendamento.domain.PerfilUsuario;
import br.com.fiap.hospital.agendamento.domain.SolicitanteAutenticado;
import br.com.fiap.hospital.agendamento.fake.ConsultaRepositoryFake;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O limite de intervalo fora da faixa e recusado <b>antes</b> de alcancar o repositorio.
 *
 * <p>"Antes" nao e detalhe de estilo. Enviado ao banco, um instante fora da faixa vira
 * {@code timestamp out of range} — uma violacao de integridade, isto e, 500 provocado por
 * um parametro de query. E foi assim que o defeito apareceu na varredura hostil.
 *
 * <p>A prova e a contagem de chamadas do repositorio: a recusa tem de acontecer com o
 * contador em zero. Mover a validacao para o adaptador, ou para depois da consulta,
 * deixaria o status HTTP igual e este teste vermelho — que e exatamente a diferenca que
 * uma assercao de status nao enxerga.
 */
@DisplayName("Fronteira do intervalo de listagem")
class FronteiraDoIntervaloDeListagemTest {

    /** Repositorio real de teste, com a unica adicao de contar as consultas recebidas. */
    static class RepositorioQueConta extends ConsultaRepositoryFake {
        int chamadasDeListar;

        @Override
        public Pagina<Consulta> listar(FiltroDeConsultas filtro) {
            chamadasDeListar++;
            return super.listar(filtro);
        }
    }

    private final RepositorioQueConta repositorio = new RepositorioQueConta();
    private final ListarConsultasUseCase listar = new ListarConsultasUseCase(repositorio);

    private static final SolicitanteAutenticado MEDICO =
            new SolicitanteAutenticado(UUID.randomUUID(), PerfilUsuario.MEDICO, null);

    /** Aceito pelo parser ISO e fora do que o armazenamento representa. */
    private static final OffsetDateTime ALEM_DO_ARMAZENAMENTO =
            OffsetDateTime.parse("+300000-01-01T00:00:00Z");

    @Test
    @DisplayName("limite 'de' fora da faixa não alcança o repositório")
    void limiteDeForaDaFaixaNaoAlcancaORepositorio() {
        recusa(consulta(OffsetDateTime.MAX, null), "de");
        recusa(consulta(OffsetDateTime.MIN, null), "de");
        recusa(consulta(ALEM_DO_ARMAZENAMENTO, null), "de");
    }

    @Test
    @DisplayName("limite 'ate' fora da faixa não alcança o repositório")
    void limiteAteForaDaFaixaNaoAlcancaORepositorio() {
        recusa(consulta(null, OffsetDateTime.MAX), "ate");
        recusa(consulta(null, OffsetDateTime.MIN), "ate");
        recusa(consulta(null, ALEM_DO_ARMAZENAMENTO), "ate");
    }

    @Test
    @DisplayName("os dois limites fora da faixa são recusados de uma vez")
    void osDoisLimitesForaDaFaixaSaoRecusados() {
        recusa(consulta(OffsetDateTime.MIN, OffsetDateTime.MAX), "de");
    }

    @Test
    @DisplayName("limite imediatamente dentro da fronteira chega ao repositório")
    void limiteDentroDaFronteiraChegaAoRepositorio() {
        listar.executar(
                consulta(FaixaTemporalSuportada.MINIMO, FaixaTemporalSuportada.MAXIMO), MEDICO);

        assertThat(repositorio.chamadasDeListar)
                .as("a fronteira adotada precisa continuar aceitando o que ela declara aceitar")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("listagem sem filtro de intervalo continua chegando ao repositório")
    void listagemSemIntervaloChegaAoRepositorio() {
        listar.executar(ListarConsultasQuery.semFiltro(), MEDICO);

        assertThat(repositorio.chamadasDeListar).isEqualTo(1);
    }

    private void recusa(ListarConsultasQuery query, String campo) {
        assertThatThrownBy(() -> listar.executar(query, MEDICO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'" + campo + "'");

        assertThat(repositorio.chamadasDeListar)
                .as("o limite fora da faixa nao pode ter virado parametro de consulta")
                .isZero();
    }

    private static ListarConsultasQuery consulta(OffsetDateTime de, OffsetDateTime ate) {
        return new ListarConsultasQuery(null, null, null, de, ate, 0,
                FiltroDeConsultas.TAMANHO_PADRAO);
    }
}
