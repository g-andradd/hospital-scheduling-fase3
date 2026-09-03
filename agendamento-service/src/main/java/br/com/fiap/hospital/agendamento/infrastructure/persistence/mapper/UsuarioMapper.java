package br.com.fiap.hospital.agendamento.infrastructure.persistence.mapper;

import br.com.fiap.hospital.agendamento.domain.Cpf;
import br.com.fiap.hospital.agendamento.domain.Crm;
import br.com.fiap.hospital.agendamento.domain.Email;
import br.com.fiap.hospital.agendamento.domain.Medico;
import br.com.fiap.hospital.agendamento.domain.Paciente;
import br.com.fiap.hospital.agendamento.domain.Usuario;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.entity.MedicoEntity;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.entity.PacienteEntity;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.entity.UsuarioEntity;
import org.springframework.stereotype.Component;

/**
 * Mapeamento manual de usuario, paciente e medico.
 *
 * <p>Os value objects sao reconstruidos pelos seus construtores, que validam formato.
 * Isso e deliberado: o banco tem constraint de unicidade, mas nao de formato, e um
 * valor invalido gravado por outro caminho deve falhar alto na leitura em vez de
 * circular pelo sistema.
 */
@Component
public class UsuarioMapper {

    public Usuario paraDominio(UsuarioEntity entidade) {
        return new Usuario(
                entidade.getId(),
                entidade.getNome(),
                new Email(entidade.getEmail()),
                entidade.getSenhaHash(),
                entidade.getPerfil(),
                entidade.isAtivo());
    }

    public Paciente paraDominio(PacienteEntity entidade) {
        return new Paciente(
                entidade.getId(),
                paraDominio(entidade.getUsuario()),
                new Cpf(entidade.getCpf()),
                entidade.getDataNascimento(),
                entidade.getTelefone());
    }

    public Medico paraDominio(MedicoEntity entidade) {
        return new Medico(
                entidade.getId(),
                paraDominio(entidade.getUsuario()),
                new Crm(entidade.getCrm()),
                entidade.getEspecialidade());
    }
}
