package br.com.fiap.hospital.agendamento.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.agendamento.domain.PerfilUsuario;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.entity.UsuarioEntity;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.repository.UsuarioJpaRepository;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * RNF-01, clausula "nunca em log": nem a senha nem o hash aparecem no log do login.
 *
 * <p>As outras tres clausulas do RNF-01 ja tinham prova — BCrypt na configuracao, ausencia na
 * resposta do caso de uso e ausencia no token. Esta nao tinha: nenhuma suite do projeto
 * inspecionava log algum atras de senha. A auditoria de requisitos do M14 expos a lacuna, e
 * este teste, ao ser escrito, encontrou o requisito <b>sendo violado</b>: o resolvedor de
 * {@code @RequestBody} do Spring MVC registrava {@code LoginRequest[email=..., senha=...]} em
 * DEBUG, porque o {@code toString()} gerado de um record inclui todos os componentes. A causa
 * foi corrigida em {@code LoginRequest}; este teste e o que impede a volta.
 *
 * <p>O nivel de captura e DEBUG de proposito. Baixa-lo para INFO faria a suite passar
 * escondendo o defeito: o RNF-01 diz "nunca em log", nao "nunca no nivel que a entrega usa".
 *
 * <p>O cliente HTTP e o {@link HttpClient} do JDK, e nao o {@code TestRestTemplate}. O
 * {@code RestTemplate} registra em DEBUG o corpo que ele proprio envia, e esse ruido do lado
 * cliente obrigaria a ignorar eventos por logger — abrindo a porta para mascarar, sem querer,
 * um vazamento de producao. O cliente do JDK nao escreve pelo Logback, entao o appender no
 * logger raiz observa exatamente o lado servidor.
 *
 * <p>Nao sobe contexto novo: as anotacoes sao as de {@code CadeiaDeSegurancaIT} e
 * {@code MatrizDeAutorizacaoIT}, entao a chave do cache de contexto do Spring e a mesma. A
 * captura por {@code ListAppender} e o padrao ja usado por {@code OutboxRelayIT} e por
 * {@code CorrelacaoHistoricoIT}.
 *
 * <p><b>Limite declarado.</b> A prova cobre o que esta JVM escreve durante os dois logins, em
 * DEBUG ou acima. Nao alcanca bibliotecas que escrevam fora do Logback, nem o que outros
 * servicos registram, nem niveis mais verbosos ligados so em producao.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Senha fora dos logs")
class SenhaForaDosLogsIT {

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registro) {
        ContainerPostgres.registrarPropriedades(registro);
    }

    /** BCrypt de Senha@123, o mesmo hash que as outras suites do agendamento semeiam. */
    private static final String HASH_SENHA_123 =
            "$2a$10$JUU8mSXfivdwzpuhR9norOIR5JKK5EcQiWSwiultOGzapLvxFTLVW";

    private static final String EMAIL = "rnf01.log@hospital.com";
    private static final String SENHA_CORRETA = "Senha@123";
    private static final String SENHA_ERRADA = "Senha@456";
    private static final String PREFIXO_DE_HASH = "$2a$10$";

    @LocalServerPort private int porta;

    @Autowired private UsuarioJpaRepository usuarioJpa;

    private final Logger raiz = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> registros = new ListAppender<>();
    private Level nivelAnterior;

    @BeforeEach
    void observarOLogInteiro() {
        // Remove apenas o proprio usuario, nunca a tabela inteira. Um deleteAll() aqui viola
        // fk_paciente_usuario quando outra suite do reactor ja deixou paciente ou medico
        // referenciando usuario — passa isolado, com o banco vazio, e quebra no build completo.
        usuarioJpa.findByEmail(EMAIL).ifPresent(usuarioJpa::delete);
        usuarioJpa.save(new UsuarioEntity(
                UUID.randomUUID(), "Fulano de Tal", EMAIL, HASH_SENHA_123,
                PerfilUsuario.MEDICO, true, OffsetDateTime.now()));

        nivelAnterior = raiz.getLevel();
        raiz.setLevel(Level.DEBUG);
        registros.start();
        raiz.addAppender(registros);
    }

    @AfterEach
    void pararDeObservar() {
        raiz.detachAppender(registros);
        registros.stop();
        raiz.setLevel(nivelAnterior);
    }

    @Test
    @DisplayName("Scenario: senha e hash não aparecem no log do login bem-sucedido nem no da recusa")
    void loginNaoEscreveSenhaNemHashNoLog() throws IOException, InterruptedException {
        HttpResponse<String> sucesso = autenticar(SENHA_CORRETA);
        HttpResponse<String> recusa = autenticar(SENHA_ERRADA);

        assertThat(sucesso.statusCode())
                .as("o login com a senha correta precisa funcionar, senão a prova não exercita o caminho")
                .isEqualTo(200);
        assertThat(recusa.statusCode())
                .as("a recusa precisa acontecer, senão o caminho de erro não é exercitado")
                .isEqualTo(401);

        List<String> capturado = textoCapturado();

        assertThat(capturado)
                .as("nenhum registro capturado — sem isso o teste passaria por não ter observado nada")
                .isNotEmpty();

        for (String segredo : List.of(SENHA_CORRETA, SENHA_ERRADA, HASH_SENHA_123, PREFIXO_DE_HASH)) {
            assertThat(vazamentos(capturado, segredo))
                    .as("segredo %s vazou para o log; cada linha abaixo é <logger> :: <trecho>", segredo)
                    .isEmpty();
        }

        assertThat(sucesso.body())
                .doesNotContain(SENHA_CORRETA)
                .doesNotContain(HASH_SENHA_123)
                .doesNotContain(PREFIXO_DE_HASH);
        assertThat(recusa.body())
                .doesNotContain(SENHA_ERRADA)
                .doesNotContain(HASH_SENHA_123)
                .doesNotContain(PREFIXO_DE_HASH);
    }

    private HttpResponse<String> autenticar(String senha) throws IOException, InterruptedException {
        String corpo = "{\"email\":\"%s\",\"senha\":\"%s\"}".formatted(EMAIL, senha);
        HttpRequest requisicao = HttpRequest
                .newBuilder(URI.create("http://localhost:" + porta + "/auth/login"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(corpo, StandardCharsets.UTF_8))
                .build();
        try (HttpClient cliente = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()) {
            return cliente.send(requisicao, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }

    /**
     * Cada trecho observado, prefixado pelo logger que o emitiu.
     *
     * <p>O prefixo existe para que uma falha aponte a origem: sem ele, "a senha apareceu no log"
     * nao diz qual componente a escreveu.
     */
    private List<String> textoCapturado() {
        List<String> texto = new ArrayList<>();
        for (ILoggingEvent evento : List.copyOf(registros.list)) {
            String origem = evento.getLoggerName();
            texto.add(origem + " :: " + evento.getFormattedMessage());
            texto.add(origem + " :: " + evento.getMessage());
            if (evento.getArgumentArray() != null) {
                for (Object argumento : evento.getArgumentArray()) {
                    texto.add(origem + " :: " + argumento);
                }
            }
            for (IThrowableProxy erro = evento.getThrowableProxy(); erro != null; erro = erro.getCause()) {
                texto.add(origem + " :: " + erro.getMessage());
                texto.add(origem + " :: " + erro.getClassName());
            }
        }
        return List.copyOf(texto);
    }

    /** Trechos que carregam o segredo, ja com o logger de origem, sem repetir. */
    private static List<String> vazamentos(List<String> capturado, String segredo) {
        return capturado.stream()
                .filter(registro -> registro.contains(segredo))
                .distinct()
                .toList();
    }
}
