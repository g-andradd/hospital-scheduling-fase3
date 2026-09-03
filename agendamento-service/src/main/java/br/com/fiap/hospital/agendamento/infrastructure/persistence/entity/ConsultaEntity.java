package br.com.fiap.hospital.agendamento.infrastructure.persistence.entity;

import br.com.fiap.hospital.agendamento.domain.StatusConsulta;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Representacao persistente da consulta.
 *
 * <p>Deliberadamente separada da {@code Consulta} de dominio. A separacao nao e
 * cerimonia: e o que garante que mutar o objeto de dominio nao agende escrita nenhuma.
 * Esta entidade e a unica gerenciada pelo {@code EntityManager}, e so o adaptador,
 * dentro de {@code salvar}, escreve nela.
 *
 * <p>{@code status} e persistido como texto. Ordinal quebraria em silencio se alguem
 * reordenasse {@code StatusConsulta} — e aquele enum ganha significado pela posicao na
 * maquina de estados, entao reordenar e plausivel.
 */
@Entity
@Table(name = "consulta")
public class ConsultaEntity {

    @Id
    private UUID id;

    @Column(name = "paciente_id", nullable = false)
    private UUID pacienteId;

    @Column(name = "medico_id", nullable = false)
    private UUID medicoId;

    @Column(name = "registrado_por_id", nullable = false)
    private UUID registradoPorId;

    @Column(name = "data_hora", nullable = false)
    private OffsetDateTime dataHora;

    @Column(name = "duracao_minutos", nullable = false)
    private int duracaoMinutos;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatusConsulta status;

    private String observacoes;

    @Column(name = "motivo_cancelamento")
    private String motivoCancelamento;

    @Column(name = "criado_em", nullable = false)
    private OffsetDateTime criadoEm;

    @Column(name = "atualizado_em", nullable = false)
    private OffsetDateTime atualizadoEm;

    /** Lock otimista. Ver D5: existe so aqui, o dominio nao carrega versao. */
    @Version
    @Column(nullable = false)
    private long versao;

    protected ConsultaEntity() {}

    public ConsultaEntity(UUID id, UUID pacienteId, UUID medicoId, UUID registradoPorId) {
        this.id = id;
        this.pacienteId = pacienteId;
        this.medicoId = medicoId;
        this.registradoPorId = registradoPorId;
    }

    /**
     * Copia o estado mutavel do dominio sobre esta entidade.
     *
     * <p>Este e o unico ponto do sistema onde a entidade gerenciada muda. Uma operacao
     * recusada nunca chega aqui, entao o flush do commit nao tem o que escrever.
     */
    public void copiarDe(
            UUID medicoId,
            OffsetDateTime dataHora,
            int duracaoMinutos,
            StatusConsulta status,
            String observacoes,
            String motivoCancelamento,
            OffsetDateTime criadoEm,
            OffsetDateTime atualizadoEm) {
        this.medicoId = medicoId;
        this.dataHora = dataHora;
        this.duracaoMinutos = duracaoMinutos;
        this.status = status;
        this.observacoes = observacoes;
        this.motivoCancelamento = motivoCancelamento;
        this.criadoEm = criadoEm;
        this.atualizadoEm = atualizadoEm;
    }

    public UUID getId() { return id; }
    public UUID getPacienteId() { return pacienteId; }
    public UUID getMedicoId() { return medicoId; }
    public UUID getRegistradoPorId() { return registradoPorId; }
    public OffsetDateTime getDataHora() { return dataHora; }
    public int getDuracaoMinutos() { return duracaoMinutos; }
    public StatusConsulta getStatus() { return status; }
    public String getObservacoes() { return observacoes; }
    public String getMotivoCancelamento() { return motivoCancelamento; }
    public OffsetDateTime getCriadoEm() { return criadoEm; }
    public OffsetDateTime getAtualizadoEm() { return atualizadoEm; }
    public long getVersao() { return versao; }
}
