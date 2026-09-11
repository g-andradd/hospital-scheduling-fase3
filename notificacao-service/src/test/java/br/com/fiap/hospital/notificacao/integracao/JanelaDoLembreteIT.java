package br.com.fiap.hospital.notificacao.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

/**
 * A janela {@code (agora, agora + 24h]} e os status elegiveis, com o relogio fixo da base.
 *
 * <p>As bordas sao semeadas com horario exato, a um minuto de cada lado. Com relogio real,
 * "exatamente 24 horas" seria uma faixa, e o teste da borda nao conseguiria falhar.
 */
@DisplayName("Janela do lembrete D-1")
class JanelaDoLembreteIT extends NotificacaoITBase {

    private static final Duration VINTE_E_QUATRO_HORAS = Duration.ofHours(24);

    @Test
    @DisplayName("Scenario: Consulta a 23h59 da execucao recebe lembrete")
    void consultaA23h59Recebe() {
        UUID consulta = agendar(AGORA.plus(Duration.ofHours(23).plusMinutes(59)), "AGENDADA");

        assertThat(lembretes.executar()).isEqualTo(1);

        assertThat(lembretesRegistrados(consulta)).isEqualTo(1);
        Mockito.verify(sender).enviar(Mockito.any());
    }

    @Test
    @DisplayName("Scenario: Consulta exatamente a 24 horas recebe lembrete")
    void consultaExatamenteA24hRecebe() {
        UUID consulta = agendar(AGORA.plus(VINTE_E_QUATRO_HORAS), "AGENDADA");

        assertThat(lembretes.executar())
                .as("o limite superior e fechado: a execucao exatamente 24h antes pega a consulta")
                .isEqualTo(1);
        assertThat(lembretesRegistrados(consulta)).isEqualTo(1);
    }

    @Test
    @DisplayName("Scenario: Consulta a 24h01 nao recebe lembrete")
    void consultaA24h01NaoRecebe() {
        UUID consulta = agendar(AGORA.plus(VINTE_E_QUATRO_HORAS).plusSeconds(60), "AGENDADA");

        assertThat(lembretes.executar()).isZero();

        assertThat(lembretesRegistrados(consulta)).isZero();
        Mockito.verify(sender, Mockito.never()).enviar(Mockito.any());
    }

    static Stream<Arguments> instanteOuPassado() {
        return Stream.of(
                Arguments.of("no instante da execucao", AGORA),
                Arguments.of("um minuto antes", AGORA.minusSeconds(60)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("instanteOuPassado")
    @DisplayName("Scenario: Consulta no instante da execucao ou no passado nao recebe lembrete")
    void consultaNoInstanteOuNoPassadoNaoRecebe(String caso, Instant dataHora) {
        UUID consulta = agendar(dataHora, "AGENDADA");

        assertThat(lembretes.executar())
                .as("o limite inferior e aberto: consulta %s nao e futura", caso)
                .isZero();
        assertThat(lembretesRegistrados(consulta)).isZero();
        Mockito.verify(sender, Mockito.never()).enviar(Mockito.any());
    }

    @Test
    @DisplayName("Scenario: Consultas agendadas e confirmadas sao lembradas")
    void agendadasEConfirmadasSaoLembradas() {
        UUID agendada = agendar(AGORA.plus(Duration.ofHours(2)), "AGENDADA");
        UUID confirmada = agendar(AGORA.plus(Duration.ofHours(3)), "CONFIRMADA");

        assertThat(lembretes.executar()).isEqualTo(2);

        assertThat(lembretesRegistrados(agendada)).isEqualTo(1);
        assertThat(lembretesRegistrados(confirmada)).isEqualTo(1);
    }

    @Test
    @DisplayName("Scenario: Consultas canceladas e realizadas nunca sao lembradas")
    void canceladasERealizadasNuncaSaoLembradas() {
        UUID cancelada = agendar(AGORA.plus(Duration.ofHours(2)), "CANCELADA");
        UUID realizada = agendar(AGORA.plus(Duration.ofHours(3)), "REALIZADA");

        assertThat(lembretes.executar()).isZero();

        assertThat(lembretesRegistrados(cancelada)).isZero();
        assertThat(lembretesRegistrados(realizada)).isZero();
        Mockito.verify(sender, Mockito.never()).enviar(Mockito.any());
    }

    @Test
    @DisplayName("Scenario: Execucao sem candidatos termina sem efeito")
    void execucaoSemCandidatosTerminaSemEfeito() {
        assertThat(lembretes.executar()).isZero();

        assertThat(quantidade("notificacao_enviada")).isZero();
        Mockito.verify(sender, Mockito.never()).enviar(Mockito.any());
    }
}
