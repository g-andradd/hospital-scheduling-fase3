package br.com.fiap.hospital.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * O que cada servico acrescenta a cadeia compartilhada, sem declarar cadeia propria.
 *
 * <p>Os dois padroes reproduzem exatamente a cadeia que existia antes desta propriedade:
 * {@code /api/**} autenticado e nenhum caminho publico alem dos fixos. Um servico que nao
 * configure nada, como o agendamento, nao muda de comportamento.
 *
 * <p>A separacao em duas listas nao e estetica. {@code /graphql} precisa de token e a
 * decisao de perfil fica no {@code @PreAuthorize} do resolver; a interface do GraphiQL e
 * pagina estatica servida <b>antes</b> de existir token algum, e no {@code denyAll} nao
 * carregaria. Uma unica lista obrigaria a escolher entre deixar a interface inacessivel e
 * abrir o endpoint de dados.
 *
 * @param autenticados caminhos que exigem token; o perfil e decidido por anotacao no metodo
 * @param publicosAdicionais caminhos abertos alem dos fixos, somados por profile
 */
@ConfigurationProperties(prefix = "hospital.security.caminhos")
public record CaminhosDeSeguranca(List<String> autenticados, List<String> publicosAdicionais) {

    private static final List<String> AUTENTICADOS_PADRAO = List.of("/api/**");

    public CaminhosDeSeguranca {
        autenticados = autenticados == null || autenticados.isEmpty()
                ? AUTENTICADOS_PADRAO
                : List.copyOf(autenticados);
        publicosAdicionais = publicosAdicionais == null
                ? List.of()
                : List.copyOf(publicosAdicionais);
    }

    String[] autenticadosComoArray() {
        return autenticados.toArray(String[]::new);
    }

    String[] publicosAdicionaisComoArray() {
        return publicosAdicionais.toArray(String[]::new);
    }
}
