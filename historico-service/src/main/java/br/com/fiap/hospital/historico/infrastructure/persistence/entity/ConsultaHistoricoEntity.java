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

    /**
     * Aplica a correcao manual do M09.
     *
     * <p>Nao ha setter para {@code id}, {@code pacienteId} nem {@code medicoId}: a
     * identidade do registro nao e corrigivel, e a ausencia do metodo e a garantia — um
     * campo que nao existe nao pode ser esquecido numa validacao.
     *
     * <p>{@code criadoEm} tambem nao muda: representa quando a projecao nasceu, e reescreve-lo
     * apagaria a diferenca entre um registro antigo corrigido e um registro novo.
     */
    public void corrigir(String pacienteNome, String medicoNome, String especialidade,
                         OffsetDateTime dataHora, String status, String observacoes,
                         Instant atualizadoEm) {
        this.pacienteNome = pacienteNome;
        this.medicoNome = medicoNome;
        this.especialidade = especialidade;
        this.dataHora = dataHora;
        this.status = status;
        this.observacoes = observacoes;
        this.atualizadoEm = atualizadoEm;
    }

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
