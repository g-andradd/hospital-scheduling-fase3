package br.com.fiap.hospital.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Le o token do cabecalho e popula o contexto de seguranca.
 *
 * <p>Nao decide nada sobre acesso: ausencia ou invalidez do token deixam o contexto
 * vazio, e quem recusa e a cadeia de filtros. Separar as duas coisas e o que permite que
 * endpoints publicos funcionem sem token, e que o motivo da recusa seja sempre o mesmo,
 * independentemente de o token estar ausente, vencido ou adulterado.
 *
 * <p>O perfil vira autoridade com o prefixo ROLE_, para que as anotacoes de metodo
 * possam consultar o papel.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String CABECALHO = "Authorization";
    private static final String PREFIXO = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest requisicao, HttpServletResponse resposta, FilterChain cadeia)
            throws ServletException, IOException {

        tokenDe(requisicao)
                .flatMap(jwtService::validar)
                .ifPresent(JwtAuthenticationFilter::autenticar);

        cadeia.doFilter(requisicao, resposta);
    }

    private static void autenticar(UsuarioAutenticado usuario) {
        var autoridades = List.of(new SimpleGrantedAuthority("ROLE_" + usuario.perfil()));
        var autenticacao = new UsernamePasswordAuthenticationToken(usuario, null, autoridades);
        SecurityContextHolder.getContext().setAuthentication(autenticacao);
    }

    /**
     * Exige o esquema Bearer seguido de espaco e de conteudo.
     *
     * <p>Cabecalho ausente, vazio, com outro esquema, sem o espaco ou sem token depois
     * dele sao todos tratados como ausencia de credencial — nunca como erro. Erro aqui
     * viraria excecao no filtro, fora do alcance do tratador global, e 500.
     */
    private static Optional<String> tokenDe(HttpServletRequest requisicao) {
        String cabecalho = requisicao.getHeader(CABECALHO);
        if (cabecalho == null || !cabecalho.startsWith(PREFIXO)) {
            return Optional.empty();
        }
        String token = cabecalho.substring(PREFIXO.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
}
