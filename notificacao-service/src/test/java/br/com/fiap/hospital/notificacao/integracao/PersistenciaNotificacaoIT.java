package br.com.fiap.hospital.notificacao.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.contracts.ConsultaPayload;
import br.com.fiap.hospital.contracts.TipoEvento;
import br.com.fiap.hospital.notificacao.domain.EventoProcessado;
import br.com.fiap.hospital.notificacao.domain.NotificacaoEnviada;
import br.com.fiap.hospital.notificacao.repository.AgendaLocalRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * O mapeamento JPA das tres entidades, lido de volta pelo proprio mapeamento.
 *
 * <p>As demais suites leem a agenda por JdbcTemplate, que e o caminho da escrita e nao prova
 * o mapeamento: uma coluna mal anotada continuaria invisivel. Aqui a leitura passa pelas
 * entidades, entao um {@code @Column} errado, um tipo incompativel ou um instante que perde
 * precisao aparecem.
 */
@DisplayName("Persistencia do notificacao_db")
class PersistenciaNotificacaoIT extends NotificacaoITBase {

    private static final Instant FATO = Instant.parse("2026-09-11T09:00:00Z");
    private static final OffsetDateTime DATA_HORA =
            OffsetDateTime.ofInstant(Instant.parse("2026-09-20T14:30:00Z"), ZoneOffset.UTC);

    @Autowired AgendaLocalRepository agendas;

    @Test
    @DisplayName("a agenda escrita pelo consumidor e lida de volta pelo mapeamento JPA")
    void agendaLocalFazRoundTripPeloMapeamento() {
        UUID consultaId = UUID.randomUUID();

        publicarEAguardar(evento(TipoEvento.CONSULTA_CRIADA, consultaId, FATO,
                ConsultaPayload.Status.AGENDADA, "Dr. Joao Lima", DATA_HORA));

        var linha = agendas.findById(consultaId).orElseThrow();
        assertThat(linha.getConsultaId()).isEqualTo(consultaId);
        assertThat(linha.getPacienteNome()).isEqualTo("Maria Souza");
        assertThat(linha.getPacienteEmail()).isEqualTo("maria@hospital.com");
        assertThat(linha.getMedicoNome()).isEqualTo("Dr. Joao Lima");
        assertThat(linha.getStatus()).isEqualTo("AGENDADA");
        assertThat(linha.getPacienteId()).isNotNull();
        assertThat(linha.getDataHora().toInstant())
                .as("timestamptz preserva o instante, nao o deslocamento original")
                .isEqualTo(DATA_HORA.toInstant());
        assertThat(linha.getOcorridoEm())
                .as("ocorrido_em guarda o instante do fato, vindo do envelope")
                .isEqualTo(FATO);
        assertThat(linha.getAtualizadoEm())
                .as("atualizado_em guarda o instante do servico, vindo do Clock")
                .isEqualTo(AGORA);
    }

    @Test
    @DisplayName("a auditoria escrita pelo consumidor e lida de volta pelo mapeamento JPA")
    void notificacaoEnviadaFazRoundTripPeloMapeamento() {
        UUID consultaId = UUID.randomUUID();

        publicarEAguardar(evento(TipoEvento.CONSULTA_CRIADA, consultaId, FATO,
                ConsultaPayload.Status.AGENDADA));

        NotificacaoEnviada registro = auditoria.findAll().getFirst();
        assertThat(registro.getId()).isNotNull();
        assertThat(registro.getConsultaId()).isEqualTo(consultaId);
        assertThat(registro.getTipo()).isEqualTo("CONSULTA_CRIADA");
        assertThat(registro.getDestinatario()).isEqualTo("maria@hospital.com");
        assertThat(registro.getCanal()).isEqualTo("LOG");
        assertThat(registro.getEnviadoEm()).isEqualTo(AGORA);
        assertThat(registro.getConteudo()).contains("Maria Souza");
    }

    @Test
    @DisplayName("a marca de processado e lida de volta pelo mapeamento JPA")
    void eventoProcessadoFazRoundTripPeloMapeamento() {
        UUID consultaId = UUID.randomUUID();
        var evento = evento(TipoEvento.CONSULTA_CRIADA, consultaId, FATO,
                ConsultaPayload.Status.AGENDADA);

        publicarEAguardar(evento);

        EventoProcessado marca = processados.findById(evento.eventId()).orElseThrow();
        assertThat(marca.getEventId()).isEqualTo(evento.eventId());
        assertThat(marca.getProcessadoEm()).isEqualTo(AGORA);
    }

    /**
     * Campos nulos do contrato continuam nulos.
     *
     * <p>{@code motivoCancelamento} so existe no cancelamento, e {@code observacoes} pode
     * vir nulo. O que nao pode acontecer e o mapeamento converter nulo em string vazia ou
     * recusar a linha — os dois transformariam ausencia de dado em dado errado.
     */
    @Test
    @DisplayName("campos opcionais do contrato nao viram valor inventado")
    void camposOpcionaisPermanecemComoVieram() {
        UUID consultaId = UUID.randomUUID();

        publicarEAguardar(evento(TipoEvento.CONSULTA_REALIZADA, consultaId, FATO,
                ConsultaPayload.Status.REALIZADA));

        var linha = agendas.findById(consultaId).orElseThrow();
        assertThat(linha.getStatus()).isEqualTo("REALIZADA");
        assertThat(auditoria.count())
                .as("realizacao nao notifica, entao nao existe auditoria para ler")
                .isZero();
        assertThat(agendas.count()).isEqualTo(1);
    }
}
