package br.com.fiap.hospital.agendamento.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("PeriodoConsulta")
class PeriodoConsultaTest {

    private static final OffsetDateTime AS_14H =
            OffsetDateTime.of(2026, 9, 10, 14, 0, 0, 0, ZoneOffset.ofHours(-3));

    private static PeriodoConsulta periodoDe30MinComecandoAs(int hora, int minuto) {
        return new PeriodoConsulta(AS_14H.withHour(hora).withMinute(minuto), 30);
    }

    @Test
    @DisplayName("o fim e o inicio somado a duracao")
    void fimEOInicioSomadoADuracao() {
        assertThat(periodoDe30MinComecandoAs(14, 0).fim()).isEqualTo(AS_14H.plusMinutes(30));
    }

    @Nested
    @DisplayName("sobreposicao com a convencao [inicio, fim)")
    class Sobreposicao {

        @Test
        @DisplayName("Scenario: Periodos adjacentes nao sao conflito — fim coincide com o inicio")
        void periodosAdjacentesNaoSeSobrepoem() {
            PeriodoConsulta das14 = periodoDe30MinComecandoAs(14, 0);
            PeriodoConsulta das1430 = periodoDe30MinComecandoAs(14, 30);

            assertThat(das14.sobrepoe(das1430)).isFalse();
            assertThat(das1430.sobrepoe(das14)).isFalse();
        }

        @Test
        @DisplayName("um minuto de invasao ja e sobreposicao")
        void invasaoDeUmMinutoESobreposicao() {
            PeriodoConsulta das14 = periodoDe30MinComecandoAs(14, 0);
            PeriodoConsulta das1429 = periodoDe30MinComecandoAs(14, 29);

            assertThat(das14.sobrepoe(das1429)).isTrue();
            assertThat(das1429.sobrepoe(das14)).isTrue();
        }

        @Test
        @DisplayName("periodos identicos se sobrepoem")
        void periodosIdenticosSeSobrepoem() {
            assertThat(periodoDe30MinComecandoAs(14, 0).sobrepoe(periodoDe30MinComecandoAs(14, 0)))
                    .isTrue();
        }

        @Test
        @DisplayName("periodo contido em outro se sobrepoe")
        void periodoContidoSeSobrepoe() {
            PeriodoConsulta longo = new PeriodoConsulta(AS_14H, 120);
            PeriodoConsulta curto = periodoDe30MinComecandoAs(14, 30);

            assertThat(longo.sobrepoe(curto)).isTrue();
            assertThat(curto.sobrepoe(longo)).isTrue();
        }

        @Test
        @DisplayName("periodos distantes nao se sobrepoem")
        void periodosDistantesNaoSeSobrepoem() {
            assertThat(periodoDe30MinComecandoAs(9, 0).sobrepoe(periodoDe30MinComecandoAs(16, 0)))
                    .isFalse();
        }

        @Test
        @DisplayName("comparar com periodo nulo e recusado")
        void compararComNuloERecusado() {
            assertThatThrownBy(() -> periodoDe30MinComecandoAs(14, 0).sobrepoe(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("posicao no tempo")
    class PosicaoNoTempo {

        @Test
        @DisplayName("comeca depois da referencia quando o inicio e posterior")
        void comecaDepoisQuandoInicioEPosterior() {
            assertThat(new PeriodoConsulta(AS_14H, 30).comecaDepoisDe(AS_14H.minusMinutes(1)))
                    .isTrue();
        }

        @Test
        @DisplayName("nao comeca depois quando o inicio coincide com a referencia")
        void naoComecaDepoisQuandoCoincide() {
            assertThat(new PeriodoConsulta(AS_14H, 30).comecaDepoisDe(AS_14H)).isFalse();
        }

        @Test
        @DisplayName("nao comeca depois quando o inicio e anterior")
        void naoComecaDepoisQuandoInicioEAnterior() {
            assertThat(new PeriodoConsulta(AS_14H, 30).comecaDepoisDe(AS_14H.plusMinutes(1)))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("construcao")
    class Construcao {

        @ParameterizedTest(name = "duracao = {0}")
        @ValueSource(ints = {0, -1, -30})
        @DisplayName("duracao nao positiva e recusada")
        void duracaoNaoPositivaERecusada(int duracao) {
            assertThatThrownBy(() -> new PeriodoConsulta(AS_14H, duracao))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("duracao");
        }

        @Test
        @DisplayName("inicio nulo e recusado")
        void inicioNuloERecusado() {
            assertThatThrownBy(() -> new PeriodoConsulta(null, 30))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("obrigatorio");
        }
    }
}
