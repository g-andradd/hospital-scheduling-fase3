package br.com.fiap.hospital.agendamento.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.agendamento.domain.PerfilUsuario;
import br.com.fiap.hospital.agendamento.domain.StatusConsulta;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.entity.ConsultaEntity;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.entity.MedicoEntity;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.entity.PacienteEntity;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.entity.UsuarioEntity;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.repository.ConsultaJpaRepository;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.repository.MedicoJpaRepository;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.repository.PacienteJpaRepository;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.repository.UsuarioJpaRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A API ponta a ponta contra Postgres real, sem dubles.
 *
 * <p>Prova o que os testes de camada web nao conseguem: que a requisicao roda dentro de
 * uma transacao e que uma escrita recusada nao deixa rastro no banco. Sem o decorador
 * transacional, a entidade gerenciada mutada antes da recusa seria escrita no flush.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("API sob transacao real")
class ApiTransacionalIT {

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registro) {
        ContainerPostgres.registrarPropriedades(registro);
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private ConsultaJpaRepository consultaJpa;
    @Autowired private PacienteJpaRepository pacienteJpa;
    @Autowired private MedicoJpaRepository medicoJpa;
    @Autowired private UsuarioJpaRepository usuarioJpa;

    private UUID pacienteId;
    private UUID medicoId;
    private UUID registranteId;
    private UUID consultaId;
    private OffsetDateTime inicioOriginal;

    @BeforeEach
    void preparar() {
        consultaJpa.deleteAll();
        pacienteJpa.deleteAll();
        medicoJpa.deleteAll();
        usuarioJpa.deleteAll();

        UsuarioEntity up = usuarioJpa.save(new UsuarioEntity(UUID.randomUUID(), "Maria Souza",
                "api.paciente@hospital.com", "$2a$10$h", PerfilUsuario.PACIENTE, true,
                OffsetDateTime.now()));
        pacienteId = pacienteJpa.save(new PacienteEntity(UUID.randomUUID(), up, "52998224725",
                LocalDate.of(1990, 5, 12), "+5561999990000")).getId();

        UsuarioEntity um = usuarioJpa.save(new UsuarioEntity(UUID.randomUUID(), "Dr. Joao Lima",
                "api.medico@hospital.com", "$2a$10$h", PerfilUsuario.MEDICO, true,
                OffsetDateTime.now()));
        medicoId = medicoJpa.save(new MedicoEntity(UUID.randomUUID(), um, "DF-11111",
                "Cardiologia")).getId();

        registranteId = usuarioJpa.save(new UsuarioEntity(UUID.randomUUID(), "Ana Enfermeira",
                "api.enfermeiro@hospital.com", "$2a$10$h", PerfilUsuario.ENFERMEIRO, true,
                OffsetDateTime.now())).getId();

        inicioOriginal = OffsetDateTime.now().plusDays(3).withNano(0);
        ConsultaEntity existente =
                new ConsultaEntity(UUID.randomUUID(), pacienteId, medicoId, registranteId);
        existente.copiarDe(medicoId, inicioOriginal, 30, StatusConsulta.AGENDADA,
                "observacao clinica", null, OffsetDateTime.now(), OffsetDateTime.now());
        consultaId = consultaJpa.saveAndFlush(existente).getId();

        // Ocupa a agenda do medico no destino, para que a remarcacao seja recusada.
        ConsultaEntity conflitante =
                new ConsultaEntity(UUID.randomUUID(), pacienteId, medicoId, registranteId);
        conflitante.copiarDe(medicoId, inicioOriginal.plusDays(1), 30, StatusConsulta.AGENDADA,
                null, null, OffsetDateTime.now(), OffsetDateTime.now());
        consultaJpa.saveAndFlush(conflitante);
    }

    private static HttpEntity<String> json(String corpo) {
        HttpHeaders cabecalhos = new HttpHeaders();
        cabecalhos.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(corpo, cabecalhos);
    }

    @Test
    @DisplayName("Scenario: Operacao executada dentro de uma transacao")
    void escritaRecusadaNaoDeixaRastro() {
        ResponseEntity<String> resposta = rest.exchange(
                "/api/v1/consultas/" + consultaId,
                HttpMethod.PUT,
                json("{\"dataHora\":\"" + inicioOriginal.plusDays(1)
                        + "\",\"observacoes\":\"nao deveria persistir\"}"),
                String.class);

        assertThat(resposta.getStatusCode().value()).isEqualTo(409);

        ConsultaEntity relida = consultaJpa.findById(consultaId).orElseThrow();
        assertThat(relida.getDataHora().toInstant())
                .as("a remarcacao recusada nao pode ter sido escrita no flush do commit")
                .isEqualTo(inicioOriginal.toInstant());
        assertThat(relida.getObservacoes()).isEqualTo("observacao clinica");
        assertThat(relida.getVersao()).as("nenhuma escrita, nenhuma versao nova").isZero();
    }

    @Test
    @DisplayName("o caminho de sucesso persiste de verdade")
    void escritaAceitaPersiste() {
        ResponseEntity<String> resposta = rest.exchange(
                "/api/v1/consultas/" + consultaId,
                HttpMethod.PUT,
                json("{\"observacoes\":\"remarcado a pedido\"}"),
                String.class);

        assertThat(resposta.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(consultaJpa.findById(consultaId).orElseThrow().getObservacoes())
                .isEqualTo("remarcado a pedido");
    }

    @Test
    @DisplayName("registro devolve 201 e a consulta fica no banco")
    void registroPersiste() {
        ResponseEntity<String> resposta = rest.postForEntity(
                "/api/v1/consultas",
                json("""
                        {"pacienteId":"%s","medicoId":"%s","registradoPorId":"%s","dataHora":"%s"}
                        """.formatted(pacienteId, medicoId, registranteId,
                        inicioOriginal.plusDays(10))),
                String.class);

        assertThat(resposta.getStatusCode().value()).isEqualTo(201);
        assertThat(resposta.getHeaders().getLocation()).isNotNull();
        assertThat(consultaJpa.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("a listagem pagina contra o banco e respeita o teto")
    void listagemPaginaContraOBanco() {
        ResponseEntity<String> resposta = rest.getForEntity(
                "/api/v1/consultas?tamanho=100000", String.class);

        assertThat(resposta.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resposta.getBody())
                .as("o corpo informa o tamanho efetivamente aplicado")
                .contains("\"tamanho\":100");
    }
}
