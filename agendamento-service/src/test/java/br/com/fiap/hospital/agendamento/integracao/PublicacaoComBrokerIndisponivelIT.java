package br.com.fiap.hospital.agendamento.integracao;

import static org.assertj.core.api.Assertions.*;
import br.com.fiap.hospital.agendamento.infrastructure.messaging.*;
import br.com.fiap.hospital.contracts.*;
import br.com.fiap.hospital.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
class PublicacaoComBrokerIndisponivelIT {
    @DynamicPropertySource static void props(DynamicPropertyRegistry p) {
        ContainerPostgres.registrarPropriedadesEmEsquema(p,"m05_http");
        M05RabbitBase.rabbit(p);
    }
    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxRelay relay;
    @Autowired RabbitTemplate rabbit;
    @Autowired EventoJson json;
    @Autowired org.springframework.context.ApplicationContext context;

    @Test 
    // Scenario: Broker indisponível não desfaz fato confirmado
    // Scenario: Correlação HTTP reaparece no header após o fim do request
    void httpConfirmaSemBrokerEEntregaDepoisComMesmoFato() throws Exception {
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(relay)).isTrue();
        assertThat(context.getBeansOfType(OutboxScheduler.class)).isEmpty();
        var medico=UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        var usuario=UUID.fromString("11111111-1111-1111-1111-111111111111");
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(jwt.emitir(new UsuarioAutenticado(usuario,"medico@hospital.com","MEDICO",null,medico)));
        headers.set("X-Correlation-Id","http-sem-broker");
        var data=OffsetDateTime.now(Clock.systemUTC()).plusDays(5).withNano(0);
        assertThat(M05RabbitBase.BROKER.execInContainer("rabbitmqctl","stop_app").getExitCode()).isZero();
        EventoEnvelope<ConsultaPayload> antes;
        try {
            var resposta=rest.postForEntity("/api/v1/consultas",new HttpEntity<>("""
                {"pacienteId":"bbbbbbbb-0000-0000-0000-000000000001","medicoId":"%s",
                 "registradoPorId":"%s","dataHora":"%s","duracaoMinutos":30}
                """.formatted(medico,usuario,data),headers),String.class);
            assertThat(resposta.getStatusCode().value()).isEqualTo(201);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM consulta",Integer.class)).isEqualTo(1);
            antes=json.ler(jdbc.queryForObject("SELECT payload::text FROM outbox_evento",String.class));
            assertThat(antes.correlationId()).isEqualTo("http-sem-broker");
            relay.executar();
            assertThat(jdbc.queryForObject("SELECT tentativas FROM outbox_evento",Integer.class)).isEqualTo(1);
        } finally {assertThat(M05RabbitBase.BROKER.execInContainer("rabbitmqctl","start_app").getExitCode()).isZero();}
        jdbc.update("UPDATE usuario SET nome='Mudou depois do request'");
        relay.executar();
        var mensagem=rabbit.receive(MensageriaAutoConfiguration.NOTIFICACAO,10000);
        assertThat(mensagem).isNotNull();
        assertThat(json.ler(new String(mensagem.getBody(),java.nio.charset.StandardCharsets.UTF_8))).isEqualTo(antes);
        assertThat(mensagem.getMessageProperties().getHeader("x-correlation-id").toString()).isEqualTo("http-sem-broker");
        assertThat(jdbc.queryForObject("SELECT publicado_em IS NOT NULL FROM outbox_evento",Boolean.class)).isTrue();
    }
}
