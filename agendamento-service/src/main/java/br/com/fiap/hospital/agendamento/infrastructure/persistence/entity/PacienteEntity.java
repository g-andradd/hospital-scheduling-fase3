package br.com.fiap.hospital.agendamento.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "paciente")
public class PacienteEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private UsuarioEntity usuario;

    @Column(nullable = false)
    private String cpf;

    @Column(name = "data_nascimento", nullable = false)
    private LocalDate dataNascimento;

    private String telefone;

    protected PacienteEntity() {}

    public PacienteEntity(UUID id, UsuarioEntity usuario, String cpf,
                          LocalDate dataNascimento, String telefone) {
        this.id = id;
        this.usuario = usuario;
        this.cpf = cpf;
        this.dataNascimento = dataNascimento;
        this.telefone = telefone;
    }

    public UUID getId() { return id; }
    public UsuarioEntity getUsuario() { return usuario; }
    public String getCpf() { return cpf; }
    public LocalDate getDataNascimento() { return dataNascimento; }
    public String getTelefone() { return telefone; }
}
