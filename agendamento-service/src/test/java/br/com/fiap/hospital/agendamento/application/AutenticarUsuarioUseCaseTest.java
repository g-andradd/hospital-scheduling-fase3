package br.com.fiap.hospital.agendamento.application;

import static br.com.fiap.hospital.agendamento.Cenario.medico;
import static br.com.fiap.hospital.agendamento.Cenario.paciente;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.fiap.hospital.agendamento.domain.Email;
import br.com.fiap.hospital.agendamento.domain.Medico;
import br.com.fiap.hospital.agendamento.domain.Paciente;
import br.com.fiap.hospital.agendamento.domain.PerfilUsuario;
import br.com.fiap.hospital.agendamento.domain.Usuario;
import br.com.fiap.hospital.agendamento.domain.exception.CredencialInvalidaException;
import br.com.fiap.hospital.agendamento.domain.port.VerificadorDeSenhaPort;
import br.com.fiap.hospital.agendamento.fake.UsuarioRepositoryFake;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Requirement: Autenticacao por e-mail e senha")
class AutenticarUsuarioUseCaseTest {

    private static final String SENHA_CORRETA = "Senha@123";

    private UsuarioRepositoryFake usuarios;
    private VerificadorDeSenhaEspiao senhas;
    private AutenticarUsuarioUseCase autenticar;

    private Paciente maria;
    private Medico joao;

    @BeforeEach
    void preparar() {
        maria = paciente();
        joao = medico();
        usuarios = new UsuarioRepositoryFake().com(maria).com(joao);
        senhas = new VerificadorDeSenhaEspiao();
        autenticar = new AutenticarUsuarioUseCase(usuarios, senhas);
    }

    private IdentidadeAutenticada autenticarCom(String email, String senha) {
        return autenticar.executar(new AutenticarUsuarioCommand(email, senha));
    }

    @Test
    @DisplayName("Scenario: Autenticacao bem-sucedida")
    void autenticacaoBemSucedida() {
        IdentidadeAutenticada identidade =
                autenticarCom(maria.usuario().email().valor(), SENHA_CORRETA);

        assertThat(identidade.usuarioId()).isEqualTo(maria.usuario().id());
        assertThat(identidade.email()).isEqualTo(maria.usuario().email().valor());
        assertThat(identidade.perfil()).isEqualTo(PerfilUsuario.PACIENTE);
        assertThat(identidade.pacienteId())
                .as("sem o identificador de paciente na identidade, o recorte da listagem "
                        + "nao tem por onde ser aplicado")
                .isEqualTo(maria.id());
        assertThat(identidade.medicoId()).isNull();
    }

    @Test
    @DisplayName("o medico se autentica com o proprio identificador, e sem o de paciente")
    void medicoAutenticaComOProprioIdentificador() {
        IdentidadeAutenticada identidade =
                autenticarCom(joao.usuario().email().valor(), SENHA_CORRETA);

        assertThat(identidade.perfil()).isEqualTo(PerfilUsuario.MEDICO);
        assertThat(identidade.medicoId()).isEqualTo(joao.id());
        assertThat(identidade.pacienteId()).isNull();
    }

    @Test
    @DisplayName("Scenario: Senha incorreta e recusada")
    void senhaIncorretaERecusada() {
        assertThatThrownBy(() -> autenticarCom(maria.usuario().email().valor(), "Senha@124"))
                .isInstanceOf(CredencialInvalidaException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("senha ausente e recusada como qualquer outra credencial invalida")
    void senhaAusenteERecusada(String senha) {
        assertThatThrownBy(() -> autenticarCom(maria.usuario().email().valor(), senha))
                .isInstanceOf(CredencialInvalidaException.class);
    }

    @Test
    @DisplayName("Scenario: E-mail inexistente e recusado da mesma forma")
    void emailInexistenteERecusadoDaMesmaForma() {
        assertThatThrownBy(() -> autenticarCom("ninguem@hospital.com", SENHA_CORRETA))
                .isInstanceOf(CredencialInvalidaException.class)
                .hasMessage(new CredencialInvalidaException().getMessage());
    }

    /**
     * A mensagem tem de ser identica nos dois casos.
     *
     * <p>Distinguir "nao achei o e-mail" de "a senha nao bate" transforma o login em
     * oraculo: quem quiser saber se alguem tem conta no hospital descobre com uma
     * requisicao.
     */
    @Test
    @DisplayName("as duas recusas sao indistinguiveis para o cliente")
    void recusasSaoIndistinguiveis() {
        Throwable porSenha = catchThrowable(
                () -> autenticarCom(maria.usuario().email().valor(), "errada"));
        Throwable porEmail = catchThrowable(
                () -> autenticarCom("ninguem@hospital.com", SENHA_CORRETA));

        assertThat(porSenha).hasSameClassAs(porEmail);
        assertThat(porSenha.getMessage()).isEqualTo(porEmail.getMessage());
        assertThat(porSenha.getMessage())
                .as("a mensagem nao pode citar o e-mail tentado")
                .doesNotContain("ninguem@hospital.com", maria.usuario().email().valor());
    }

    @Test
    @DisplayName("Scenario: Usuario inativo e recusado")
    void usuarioInativoERecusado() {
        Usuario desativado = new Usuario(UUID.randomUUID(), "Ex Funcionario",
                new Email("inativo@hospital.com"), "$2a$10$hash", PerfilUsuario.MEDICO, false);
        usuarios.com(desativado);

        assertThatThrownBy(() -> autenticarCom("inativo@hospital.com", SENHA_CORRETA))
                .as("desativar um usuario tem de bastar; a senha dele continua correta")
                .isInstanceOf(CredencialInvalidaException.class);
    }

    /**
     * A defesa contra enumeracao por tempo.
     *
     * <p>Sem a verificacao forcada, a rota do e-mail inexistente volta em
     * microssegundos e a do e-mail existente gasta as dezenas de milissegundos do
     * BCrypt. A diferenca e medivel de fora e responde exatamente a pergunta que a
     * mensagem unica se recusa a responder.
     *
     * <p>Medir tempo em teste seria instavel. O que se verifica e o mecanismo: que o
     * caminho sem usuario tambem consome o algoritmo.
     */
    @Test
    @DisplayName("Scenario: A recusa nao vaza pelo tempo de resposta")
    void recusaNaoVazaPeloTempoDeResposta() {
        assertThatThrownBy(() -> autenticarCom("ninguem@hospital.com", SENHA_CORRETA))
                .isInstanceOf(CredencialInvalidaException.class);

        assertThat(senhas.consumiuTempoSemUsuario)
                .as("sem consumir o tempo de verificacao, o e-mail inexistente responde "
                        + "rapido demais e a diferenca enumera quem tem conta")
                .isTrue();
    }

    @Test
    @DisplayName("o caminho com usuario tambem executa a verificacao, e so uma vez")
    void caminhoComUsuarioVerificaUmaVez() {
        autenticarCom(maria.usuario().email().valor(), SENHA_CORRETA);

        assertThat(senhas.senhasVerificadas).hasSize(1);
    }

    @Test
    @DisplayName("a identidade devolvida nao carrega senha nem hash")
    void identidadeNaoCarregaSenha() {
        IdentidadeAutenticada identidade =
                autenticarCom(maria.usuario().email().valor(), SENHA_CORRETA);

        assertThat(identidade.toString())
                .doesNotContain(SENHA_CORRETA)
                .doesNotContain("$2a$")
                .doesNotContain("hash");
    }

    private static Throwable catchThrowable(Runnable acao) {
        try {
            acao.run();
            return null;
        } catch (Throwable e) {
            return e;
        }
    }

    /** Registra o que foi verificado, para que o mecanismo de defesa seja observavel. */
    private static final class VerificadorDeSenhaEspiao implements VerificadorDeSenhaPort {

        private final List<String> senhasVerificadas = new ArrayList<>();
        private boolean consumiuTempoSemUsuario;

        @Override
        public boolean confere(String senhaEmClaro, String hash) {
            senhasVerificadas.add(senhaEmClaro);
            return SENHA_CORRETA.equals(senhaEmClaro);
        }

        @Override
        public void consumirTempoDeVerificacao() {
            consumiuTempoSemUsuario = true;
        }
    }
}
