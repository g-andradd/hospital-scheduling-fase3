package br.com.fiap.hospital.agendamento.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "medico")
public class MedicoEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private UsuarioEntity usuario;

    @Column(nullable = false)
    private String crm;

    @Column(nullable = false)
    private String especialidade;

    protected MedicoEntity() {}

    public MedicoEntity(UUID id, UsuarioEntity usuario, String crm, String especialidade) {
        this.id = id;
        this.usuario = usuario;
        this.crm = crm;
        this.especialidade = especialidade;
    }

    public UUID getId() { return id; }
    public UsuarioEntity getUsuario() { return usuario; }
    public String getCrm() { return crm; }
    public String getEspecialidade() { return especialidade; }
}
