package br.com.fiap.hospital.agendamento.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Todo endpoint sob {@code /api} tem uma decisao de autorizacao explicita.
 *
 * <p>A cadeia de filtros exige autenticacao em {@code /api/**} e nega o resto, mas nao
 * distingue perfis — isso e trabalho do {@code @PreAuthorize} no metodo. Um metodo novo
 * sem anotacao fica, portanto, aberto a <b>qualquer usuario autenticado</b>: o paciente
 * alcanca o que era de medico, e nada falha. A cadeia nao pega, porque a requisicao esta
 * autenticada; a matriz da §3 nao pega, porque a linha correspondente nao existe no
 * documento — o endpoint e novo.
 *
 * <p>Esta varredura e a unica protecao contra esse esquecimento, e ela nao depende de
 * ninguem lembrar: o metodo novo aparece por reflexao no dia em que e escrito.
 */
@DisplayName("Cobertura de autorizacao dos controllers")
class CoberturaDeAutorizacaoTest {

    /** Controllers cujos metodos exigem decisao de perfil. */
    private static final List<Class<?>> CONTROLLERS_DA_API = List.of(ConsultaController.class);

    private static final List<Class<? extends Annotation>> MAPEAMENTOS = List.of(
            RequestMapping.class, GetMapping.class, PostMapping.class,
            PutMapping.class, PatchMapping.class, DeleteMapping.class);

    static Stream<Method> endpoints() {
        return CONTROLLERS_DA_API.stream()
                .flatMap(c -> Arrays.stream(c.getDeclaredMethods()))
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> !m.isSynthetic())
                .filter(CoberturaDeAutorizacaoTest::eEndpoint);
    }

    private static boolean eEndpoint(Method metodo) {
        return MAPEAMENTOS.stream().anyMatch(a -> metodo.isAnnotationPresent(a));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("cada endpoint declara @PreAuthorize")
    void cadaEndpointDeclaraPreAuthorize(Method endpoint) {
        assertThat(endpoint.isAnnotationPresent(PreAuthorize.class))
                .as("%s.%s nao declara @PreAuthorize e fica aberto a qualquer usuario "
                        + "autenticado — inclusive PACIENTE",
                        endpoint.getDeclaringClass().getSimpleName(), endpoint.getName())
                .isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("cada @PreAuthorize menciona ao menos um perfil conhecido")
    void cadaPreAuthorizeMencionaPerfilConhecido(Method endpoint) {
        PreAuthorize anotacao = endpoint.getAnnotation(PreAuthorize.class);
        if (anotacao == null) {
            return; // o teste acima ja reprova; aqui so evitamos um NPE ruidoso
        }

        assertThat(anotacao.value())
                .as("%s tem uma expressao que nao cita perfil algum: permitiria todo mundo "
                        + "com aparencia de regra", endpoint.getName())
                .containsAnyOf("MEDICO", "ENFERMEIRO", "PACIENTE");
    }

    /**
     * Sem esta assercao a varredura poderia estar vazia e passar.
     *
     * <p>Um filtro de reflexao errado — anotacao que deixou de ser detectada, metodo que
     * mudou de visibilidade — devolveria stream vazio, e os testes parametrizados acima
     * passariam sem verificar endpoint algum.
     */
    @Test
    @DisplayName("a varredura encontrou os seis endpoints do ConsultaController")
    void varreduraEncontrouOsEndpoints() {
        assertThat(endpoints().toList())
                .as("a §3 descreve seis operacoes de consulta; se a reflexao parar de "
                        + "enxerga-las, os testes acima ficam decorativos")
                .hasSize(6);
    }

    /**
     * O login e a excecao, e precisa continuar sendo.
     *
     * <p>Ele e publico por definicao — exigir credencial para obter credencial nao
     * fecharia. Mas a excecao tem de ser deliberada: se alguem anotar o login com
     * {@code @PreAuthorize}, ninguem mais consegue autenticar, e a falha aparece como
     * "login retorna 401" sem nenhuma pista da causa.
     */
    @Test
    @DisplayName("o login nao declara @PreAuthorize — ele e publico por definicao")
    void loginNaoDeclaraPreAuthorize() {
        assertThat(Arrays.stream(AutenticacaoController.class.getDeclaredMethods())
                        .filter(CoberturaDeAutorizacaoTest::eEndpoint)
                        .toList())
                .isNotEmpty()
                .allSatisfy(m -> assertThat(m.isAnnotationPresent(PreAuthorize.class))
                        .as("%s exigiria credencial para obter credencial", m.getName())
                        .isFalse());
    }
}
