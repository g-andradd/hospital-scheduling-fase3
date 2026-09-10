package br.com.fiap.hospital.historico.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "consulta_historico")
public class ConsultaHistoricoEntity {
    @Id private UUID id;
    @Column(name = "paciente_id", nullable = false) private UUID pacienteId;
    @Column(name = "paciente_nome", nullable = false) private String pacienteNome;
    @Column(name = "medico_id", nullable = false) private UUID medicoId;
    @Column(name = "medico_nome", nullable = false) private String medicoNome;
    @Column(nullable = false) private String especialidade;
    @Column(name = "data_hora", nullable = false) private OffsetDateTime dataHora;
    @Column(nullable = false) private String status;
    private String observacoes;
    @Column(name = "criado_em", nullable = false) private Instant criadoEm;
    @Column(name = "atualizado_em", nullable = false) private Instant atualizadoEm;

    protected ConsultaHistoricoEntity() { }
    public UUID getId() { return id; }
    public UUID getPacienteId() { return pacienteId; }
    public String getPacienteNome() { return pacienteNome; }
    public UUID getMedicoId() { return medicoId; }
    public String getMedicoNome() { return medicoNome; }
    public String getEspecialidade() { return especialidade; }
    public OffsetDateTime getDataHora() { return dataHora; }
    public String getStatus() { return status; }
    public String getObservacoes() { return observacoes; }
    public Instant getCriadoEm() { return criadoEm; }
    public Instant getAtualizadoEm() { return atualizadoEm; }
}
