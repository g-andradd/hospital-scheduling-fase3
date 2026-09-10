package br.com.fiap.hospital.historico.integracao;

import br.com.fiap.hospital.historico.infrastructure.persistence.repository.ConsultaEventoJpaRepository;
import br.com.fiap.hospital.historico.infrastructure.persistence.repository.ConsultaHistoricoJpaRepository;
import br.com.fiap.hospital.historico.infrastructure.persistence.repository.EventoProcessadoJpaRepository;
import br.com.fiap.hospital.security.JwtService;
import br.com.fiap.hospital.security.UsuarioAutenticado;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base das suites de GraphQL: aplicacao real, seguranca real, PostgreSQL real.
 *
 * <p>As requisicoes vao por HTTP cru, e nao por um cliente de GraphQL de teste. A razao e
 * que boa parte do que precisa ser provado acontece <b>fora</b> da execucao da operacao:
 * 401 na fronteira, documento recusado na analise, GraphiQL alcancavel ou nao. Um cliente
 * que so entende resposta de operacao esconderia exatamente esses casos.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"logging.level.root=ERROR",
                // O listener AMQP fica parado nestes contextos. Os containers de teste sao
                // compartilhados, entao um consumidor ativo aqui competiria pela fila
                // historico.consultas com as suites do M08 — que publicam de verdade e
                // contam tentativas. Elas falhariam por mensagem consumida por outro
                // contexto, e o sintoma apareceria longe da causa. A projecao continua
                // testavel: CorrecaoEProjecaoIT chama o consumidor diretamente.
                "spring.rabbitmq.listener.simple.auto-startup=false"})
@Import(GraphqlITBase.RelogioFixo.class)
abstract class GraphqlITBase {

    /**
     * O relogio precisa ser fixo para a fronteira do instante atual ser testavel.
     *
     * <p>Com {@code systemUTC}, um registro criado "exatamente agora" ja seria passado
     * quando a asercao rodasse, e o teste de FUTURAS oscilaria entre verde e vermelho sem
     * ninguem ter mudado codigo.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class RelogioFixo {
        @Bean
        @Primary
        Clock relogioFixo() {
            return Clock.fixed(AGORA, ZoneOffset.UTC);
        }
    }

    static final Instant AGORA = Instant.parse("2026-09-10T12:00:00Z");

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registro) {
        ContainersDoHistorico.registrar(registro);
        registro.add(SqlCapturado.PROPRIEDADE, SqlCapturado.class::getName);
    }

    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwtService;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired ConsultaHistoricoJpaRepository snapshots;
    @Autowired ConsultaEventoJpaRepository trilha;
    @Autowired EventoProcessadoJpaRepository processados;

    final UUID pacienteA = UUID.randomUUID();
    final UUID pacienteB = UUID.randomUUID();
    final UUID medicoA = UUID.randomUUID();
    final UUID medicoB = UUID.randomUUID();

    @BeforeEach
    void limparHistorico() {
        jdbc.execute("TRUNCATE consulta_evento, evento_processado, consulta_historico CASCADE");
        SqlCapturado.limpar();
    }

    // --- identidades -------------------------------------------------------------------

    String tokenMedico() {
        return jwtService.emitir(new UsuarioAutenticado(
                UUID.randomUUID(), "medico@hospital.com", "MEDICO", null, medicoA));
    }

    String tokenEnfermeiro() {
        return jwtService.emitir(new UsuarioAutenticado(
                UUID.randomUUID(), "enfermeiro@hospital.com", "ENFERMEIRO", null, null));
    }

    String tokenPaciente(UUID pacienteId) {
        return jwtService.emitir(new UsuarioAutenticado(
                UUID.randomUUID(), "paciente@hospital.com", "PACIENTE", pacienteId, null));
    }

    // --- dados -------------------------------------------------------------------------

    UUID inserirConsulta(UUID pacienteId, UUID medicoId, OffsetDateTime dataHora, String status) {
        return inserirConsulta(pacienteId, medicoId, dataHora, status, "observacao inicial");
    }

    UUID inserirConsulta(UUID pacienteId, UUID medicoId, OffsetDateTime dataHora, String status,
                         String observacoes) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO consulta_historico
                    (id, paciente_id, paciente_nome, medico_id, medico_nome, especialidade,
                     data_hora, status, observacoes, criado_em, atualizado_em)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, pacienteId, "Paciente " + pacienteId.toString().substring(0, 4),
                medicoId, "Dr " + medicoId.toString().substring(0, 4), "Cardiologia",
                java.sql.Timestamp.from(dataHora.toInstant()), status, observacoes,
                java.sql.Timestamp.from(AGORA), java.sql.Timestamp.from(AGORA));
        return id;
    }

    static OffsetDateTime instante(String iso) {
        return OffsetDateTime.parse(iso).withOffsetSameInstant(ZoneOffset.UTC);
    }

    // --- transporte --------------------------------------------------------------------

    /** POST cru em /graphql: preserva status HTTP e corpo, inclusive quando nao ha corpo GraphQL. */
    ResponseEntity<String> postar(String token, String documento, Map<String, Object> variaveis) {
        HttpHeaders cabecalhos = new HttpHeaders();
        cabecalhos.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            cabecalhos.setBearerAuth(token);
        }
        String corpo;
        try {
            corpo = mapper.writeValueAsString(variaveis == null
                    ? Map.of("query", documento)
                    : Map.of("query", documento, "variables", variaveis));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return rest.exchange("/graphql", HttpMethod.POST,
                new HttpEntity<>(corpo, cabecalhos), String.class);
    }

    RespostaGraphql executar(String token, String documento, Map<String, Object> variaveis) {
        ResponseEntity<String> resposta = postar(token, documento, variaveis);
        try {
            return new RespostaGraphql(resposta.getStatusCode().value(),
                    mapper.readTree(resposta.getBody() == null ? "{}" : resposta.getBody()));
        } catch (Exception e) {
            throw new IllegalStateException("Resposta nao e JSON: " + resposta.getBody(), e);
        }
    }

    RespostaGraphql executar(String token, String documento) {
        return executar(token, documento, null);
    }

    /** Resposta GraphQL como o cliente a ve: status HTTP, dados e erros com seus codigos. */
    record RespostaGraphql(int statusHttp, JsonNode corpo) {

        JsonNode dados(String operacao) {
            return corpo.path("data").path(operacao);
        }

        List<String> ids(String operacao) {
            return dados(operacao).findValuesAsText("id");
        }

        boolean temErro() {
            return corpo.has("errors") && !corpo.path("errors").isEmpty();
        }

        String primeiroCodigo() {
            return corpo.path("errors").path(0).path("extensions").path("code").asText(null);
        }

        String primeiraMensagem() {
            return corpo.path("errors").path(0).path("message").asText("");
        }
    }
}
