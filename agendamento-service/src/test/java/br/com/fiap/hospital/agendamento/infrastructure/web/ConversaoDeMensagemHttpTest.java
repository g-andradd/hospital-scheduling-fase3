package br.com.fiap.hospital.agendamento.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * O tratador do supertipo precisa distinguir leitura de escrita.
 *
 * <p>{@code HttpMessageConversionException} cobre os dois sentidos da conversao. Ler um
 * corpo malformado e erro do cliente; falhar ao <b>escrever</b> a resposta e defeito do
 * servico. Sem a guarda, o mesmo tratador responderia 400 para os dois, e um bug de
 * serializacao apareceria para o cliente como "requisicao malformada" — exatamente o
 * tipo de mascaramento que o advice existe para impedir.
 *
 * <p>Por isso o teste exercita o metodo diretamente: numa requisicao HTTP real a falha de
 * escrita acontece depois de o status ja ter sido decidido, e o efeito da guarda ficaria
 * invisivel. Aqui ele fica explicito — remover a guarda deixa
 * {@link #falhaDeEscritaERelancadaEmVezDeVirar400()} vermelho.
 */
@DisplayName("Conversao de mensagem HTTP")
class ConversaoDeMensagemHttpTest {

    private final TratadorGlobalDeErros tratador = new TratadorGlobalDeErros(
            Clock.fixed(Instant.parse("2026-09-11T12:00:00Z"), ZoneOffset.UTC));

    private final MockHttpServletRequest requisicao =
            new MockHttpServletRequest("PATCH", "/api/v1/consultas/1/cancelar");

    @Test
    @DisplayName("falha de leitura do corpo vira 400 em Problem Detail")
    void falhaDeLeituraVira400() {
        ProblemDetail problema = tratador.corpoIlegivel(
                new HttpMessageConversionException(
                        "Type definition error: [simple type, class java.lang.String]"),
                requisicao);

        assertThat(problema.getStatus()).isEqualTo(400);
        assertThat(problema.getType())
                .hasToString("https://hospital.fiap.br/erros/requisicao-malformada");
        assertThat(problema.getDetail())
                .as("o detalhe e estavel por categoria, nunca a mensagem do framework")
                .isEqualTo("Corpo da requisicao malformado ou ausente")
                .doesNotContain("Type definition", "java.lang");
        assertThat(problema.getProperties())
                .containsKeys("correlationId", "timestamp");
    }

    @Test
    @DisplayName("falha de escrita da resposta é relançada, e não convertida em 400")
    void falhaDeEscritaERelancadaEmVezDeVirar400() {
        HttpMessageNotWritableException falhaDeEscrita =
                new HttpMessageNotWritableException("nao foi possivel escrever a resposta");

        assertThatThrownBy(() -> tratador.corpoIlegivel(falhaDeEscrita, requisicao))
                .as("defeito de serializacao da resposta e falha de servidor; responder 400 "
                        + "esconderia o bug atras de um erro do cliente")
                .isSameAs(falhaDeEscrita);
    }

    @Test
    @DisplayName("a subclasse de leitura continua coberta pela família do Spring MVC")
    void subclasseDeLeituraContinuaCobertaPelaFamiliaDoMvc() {
        assertThat(HttpMessageConversionException.class)
                .isAssignableFrom(HttpMessageNotReadableException.class)
                .isAssignableFrom(HttpMessageNotWritableException.class);

        ProblemDetail problema = tratador.corpoIlegivel(
                new HttpMessageNotReadableException("corpo ilegivel"), requisicao);

        assertThat(problema.getStatus())
                .as("mesmo quando chega pela subclasse de leitura, a resposta e 400")
                .isEqualTo(400);
    }
}
