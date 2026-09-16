package br.com.fiap.hospital.agendamento.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.agendamento.application.AgendarConsultaCommand;
import br.com.fiap.hospital.agendamento.contrato.ComparacaoComFixture;
import br.com.fiap.hospital.agendamento.contrato.FixtureDoContrato;
import br.com.fiap.hospital.agendamento.domain.PerfilUsuario;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.entity.MedicoEntity;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.entity.PacienteEntity;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.entity.UsuarioEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/**
 * O produtor emite, para o fato do exemplar, um envelope compativel com o exemplar.
 *
 * <p>O fato e montado a partir do proprio exemplar — paciente, medico, registrante, horario,
 * duracao e observacoes —, persistido pelo caso de uso real e publicado pelo relay real. A
 * comparacao e feita sobre a mensagem que chegou a fila normativa, e nao sobre o que foi
 * gravado no outbox: e ela que os consumidores recebem.
 */
@DisplayName("Compatibilidade do produtor com o exemplar canonico")
class CompatibilidadeDoProdutorComFixtureIT extends M05RabbitBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** CPF valido e distinto dos da base: o exemplar nao publica CPF, entao o valor e livre. */
    private static final String CPF_DO_PACIENTE_DO_EXEMPLAR = "39053344705";

    @Test
    @DisplayName("Scenario: Produtor emite envelope compativel com o exemplar")
    void produtorEmiteEnvelopeCompativelComOExemplar() throws Exception {
        FixtureDoContrato.exigirOrigemNoTestJar();
        JsonNode exemplar = MAPPER.readTree(FixtureDoContrato.texto());
        JsonNode payload = exemplar.path("payload");
        semearPessoasDo(payload);

        var criada = agendar.executar(new AgendarConsultaCommand(
                uuid(payload.path("paciente").path("id")),
                uuid(payload.path("medico").path("id")),
                uuid(payload.path("registradoPor").path("id")),
                OffsetDateTime.parse(payload.path("dataHora").textValue()),
                payload.path("duracaoMinutos").intValue(),
                payload.path("observacoes").textValue()));
        relay.executar();

        Message mensagem = receber(N);
        MessageProperties propriedades = mensagem.getMessageProperties();
        JsonNode produzido = MAPPER.readTree(mensagem.getBody());
        var contexto = new ComparacaoComFixture.Contexto(
                criada.id(),
                RELOGIO.instant(),
                String.valueOf((Object) propriedades.getHeader("x-event-id")),
                String.valueOf((Object) propriedades.getHeader("x-correlation-id")));

        assertThat(ComparacaoComFixture.divergencias(exemplar, produzido, contexto))
                .as("o envelope publicado precisa ter os mesmos campos, tipos e valores do "
                        + "exemplar, com os campos gerados coerentes")
                .isEmpty();
        assertThat(String.valueOf((Object) propriedades.getHeader("x-event-type")))
                .isEqualTo(exemplar.path("eventType").textValue());
        assertThat(propriedades.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(propriedades.getReceivedRoutingKey()).isEqualTo("consulta.criada");
    }

    /** Cadastra as pessoas do exemplar com os mesmos identificadores e valores publicados. */
    private void semearPessoasDo(JsonNode payload) {
        JsonNode paciente = payload.path("paciente");
        UsuarioEntity usuarioDoPaciente = usuarios.save(new UsuarioEntity(UUID.randomUUID(),
                paciente.path("nome").textValue(), paciente.path("email").textValue(),
                "hash-nao-publicavel", PerfilUsuario.PACIENTE, true, AGORA));
        pacientes.save(new PacienteEntity(uuid(paciente.path("id")), usuarioDoPaciente,
                CPF_DO_PACIENTE_DO_EXEMPLAR, LocalDate.of(1990, 1, 1),
                paciente.path("telefone").textValue()));

        JsonNode medico = payload.path("medico");
        UsuarioEntity usuarioDoMedico = usuarios.save(new UsuarioEntity(UUID.randomUUID(),
                medico.path("nome").textValue(), "medico.exemplar@hospital.com",
                "hash-nao-publicavel", PerfilUsuario.MEDICO, true, AGORA));
        medicos.save(new MedicoEntity(uuid(medico.path("id")), usuarioDoMedico,
                medico.path("crm").textValue(), medico.path("especialidade").textValue()));

        JsonNode registrante = payload.path("registradoPor");
        usuarios.save(new UsuarioEntity(uuid(registrante.path("id")),
                registrante.path("nome").textValue(), "registrante.exemplar@hospital.com",
                "hash-nao-publicavel", PerfilUsuario.valueOf(registrante.path("perfil").textValue()),
                true, AGORA));
    }

    private static UUID uuid(JsonNode no) {
        return UUID.fromString(no.textValue());
    }
}
