package br.com.fiap.hospital.notificacao.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * O que o servico sabe sobre uma consulta, alimentado apenas por eventos.
 *
 * <p>{@code ocorridoEm} guarda o instante do fato ja aplicado, e nao o instante do
 * consumo. E o criterio de monotonicidade: um evento com {@code occurredAt} anterior a
 * este valor nao pode sobrescrever a linha.
 */
@Entity
@Table(name = "agenda_local")
public class AgendaLocal {

    @Id
    @Column(name = "consulta_id")
    private UUID consultaId;

    @Column(name = "paciente_id", nullable = false) private UUID pacienteId;
    @Column(name = "paciente_nome", nullable = false) private String pacienteNome;
    @Column(name = "paciente_email", nullable = false) private String pacienteEmail;
    @Column(name = "medico_nome", nullable = false) private String medicoNome;
    @Column(name = "data_hora", nullable = false) private OffsetDateTime dataHora;
    @Column(nullable = false) private String status;
    @Column(name = "ocorrido_em", nullable = false) private Instant ocorridoEm;
    @Column(name = "atualizado_em", nullable = false) private Instant atualizadoEm;

    protected AgendaLocal() { }

    public UUID getConsultaId() { return consultaId; }
    public UUID getPacienteId() { return pacienteId; }
    public String getPacienteNome() { return pacienteNome; }
    public String getPacienteEmail() { return pacienteEmail; }
    public String getMedicoNome() { return medicoNome; }
    public OffsetDateTime getDataHora() { return dataHora; }
    public String getStatus() { return status; }
    public Instant getOcorridoEm() { return ocorridoEm; }
    public Instant getAtualizadoEm() { return atualizadoEm; }
}
