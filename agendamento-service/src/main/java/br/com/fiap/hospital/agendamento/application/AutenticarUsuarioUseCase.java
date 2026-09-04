package br.com.fiap.hospital.agendamento.application;

import br.com.fiap.hospital.agendamento.domain.Medico;
import br.com.fiap.hospital.agendamento.domain.Paciente;
import br.com.fiap.hospital.agendamento.domain.Usuario;
import br.com.fiap.hospital.agendamento.domain.exception.CredencialInvalidaException;
import br.com.fiap.hospital.agendamento.domain.port.UsuarioRepositoryPort;
import br.com.fiap.hospital.agendamento.domain.port.VerificadorDeSenhaPort;
import java.util.Optional;

/**
 * Autentica por e-mail e senha.
 *
 * <p>Nao emite token: devolve a identidade. JWT e detalhe de transporte, e este pacote
 * nao precisa saber que ele existe — quem emite e a borda web.
 */
public class AutenticarUsuarioUseCase {

    private final UsuarioRepositoryPort usuarios;
    private final VerificadorDeSenhaPort senhas;

    public AutenticarUsuarioUseCase(UsuarioRepositoryPort usuarios, VerificadorDeSenhaPort senhas) {
        this.usuarios = usuarios;
        this.senhas = senhas;
    }

    /**
     * @throws CredencialInvalidaException para e-mail inexistente, senha errada ou
     *     usuario inativo, indistintamente
     */
    public IdentidadeAutenticada executar(AutenticarUsuarioCommand comando) {
        Optional<Usuario> encontrado = usuarios.buscarUsuarioPorEmail(comando.email());

        // A verificacao de senha SEMPRE roda, mesmo sem usuario. Sem isto, a rota do
        // e-mail inexistente retorna em microssegundos e a do e-mail valido gasta as
        // dezenas de milissegundos do algoritmo: a diferenca e medivel de fora e permite
        // enumerar quais e-mails existem.
        if (encontrado.isEmpty()) {
            senhas.consumirTempoDeVerificacao();
            throw new CredencialInvalidaException();
        }

        Usuario usuario = encontrado.get();
        boolean senhaConfere = senhas.confere(comando.senha(), usuario.senhaHash());

        if (!senhaConfere || !usuario.ativo()) {
            throw new CredencialInvalidaException();
        }

        return new IdentidadeAutenticada(
                usuario.id(),
                usuario.email().valor(),
                usuario.perfil(),
                usuarios.buscarPacientePorUsuario(usuario.id()).map(Paciente::id).orElse(null),
                usuarios.buscarMedicoPorUsuario(usuario.id()).map(Medico::id).orElse(null));
    }
}
