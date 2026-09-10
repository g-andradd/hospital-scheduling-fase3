package br.com.fiap.hospital.notificacao.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Registro do que uma transacao confirmada produziu.
 *
 * <p>Nao e um livro-razao do transporte: um envio que conclui e cuja transacao reverte
 * nao deixa linha aqui. Ver a decisao D5 do design antes de usar esta tabela para
 * responder se um paciente foi avisado.
 */
@Entity
@Table(name = "notificacao_enviada")
public class NotificacaoEnviada {

    @Id private UUID id;
    @Column(name = "consulta_id", nullable = false) private UUID consultaId;
    @Column(nullable = false) private String tipo;
    @Column(nullable = false) private String destinatario;
    @Column(nullable = false) private String canal;
    @Column(name = "enviado_em", nullable = false) private Instant enviadoEm;
    @Column(nullable = false) private String conteudo;

    protected NotificacaoEnviada() { }

    public NotificacaoEnviada(UUID id, UUID consultaId, String tipo, String destinatario,
                              String canal, Instant enviadoEm, String conteudo) {
        this.id = id;
        this.consultaId = consultaId;
        this.tipo = tipo;
        this.destinatario = destinatario;
        this.canal = canal;
        this.enviadoEm = enviadoEm;
        this.conteudo = conteudo;
    }

    public UUID getId() { return id; }
    public UUID getConsultaId() { return consultaId; }
    public String getTipo() { return tipo; }
    public String getDestinatario() { return destinatario; }
    public String getCanal() { return canal; }
    public Instant getEnviadoEm() { return enviadoEm; }
    public String getConteudo() { return conteudo; }
}
