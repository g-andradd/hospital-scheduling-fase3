package br.com.fiap.hospital.agendamento.infrastructure.web;

import br.com.fiap.hospital.agendamento.application.AutenticarUsuarioCommand;
import br.com.fiap.hospital.agendamento.application.IdentidadeAutenticada;
import br.com.fiap.hospital.agendamento.infrastructure.transacao.AutenticarUsuarioUseCaseTransacional;
import br.com.fiap.hospital.security.JwtService;
import br.com.fiap.hospital.security.UsuarioAutenticado;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Autenticacao por e-mail e senha.
 *
 * <p>Publico por configuracao da cadeia de filtros, e o unico endpoint sem
 * {@code @PreAuthorize} — quem ainda nao se autenticou nao tem perfil a verificar.
 *
 * <p>A emissao do token acontece aqui, e nao no caso de uso: JWT e detalhe de
 * transporte, e {@code application} nao precisa saber que ele existe.
 */
@RestController
@RequestMapping("/auth")
@Tag(name = "Autenticacao", description = "Emissao de token de acesso")
public class AutenticacaoController {

    private final AutenticarUsuarioUseCaseTransacional autenticar;
    private final JwtService jwtService;

    public AutenticacaoController(
            AutenticarUsuarioUseCaseTransacional autenticar, JwtService jwtService) {
        this.autenticar = autenticar;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    @Operation(
            summary = "Autentica e devolve o token de acesso",
            description = "Credencial invalida responde 401 generico, sem informar se o "
                    + "e-mail existe.")
    public LoginResponse login(@Valid @RequestBody LoginRequest requisicao) {
        IdentidadeAutenticada identidade = autenticar.executar(
                new AutenticarUsuarioCommand(requisicao.email(), requisicao.senha()));

        String token = jwtService.emitir(new UsuarioAutenticado(
                identidade.usuarioId(),
                identidade.email(),
                identidade.perfil().name(),
                identidade.pacienteId(),
                identidade.medicoId()));

        return new LoginResponse(token, jwtService.expiracaoEmSegundos(), identidade.perfil());
    }
}
