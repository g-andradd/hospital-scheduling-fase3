package br.com.fiap.hospital.agendamento.arquitetura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import br.com.fiap.hospital.arquitetura.sintetico.a3.positivo.application.CompletoUseCase;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sensibilidade das regras A1 a A7: cada uma reprova as fontes sinteticas que a violam,
 * identificando a classe, e aceita os casos que o D2 declara conformes.
 *
 * <p>As fontes ficam fora de {@code br.com.fiap.hospital.agendamento}, para que nenhum
 * contexto Spring do servico as encontre por component scan.
 */
@DisplayName("Regras de arquitetura sobre fontes sintéticas")
class RegrasDeArquiteturaTest {

    private static final String SINTETICO = "br.com.fiap.hospital.arquitetura.sintetico.";

    private static List<String> violacoes(String caso, Function<RegrasDeArquitetura, ArchRule> regra) {
        String base = SINTETICO + caso;
        return regra.apply(new RegrasDeArquitetura(base))
                .evaluate(RegrasDeArquitetura.importarSinteticas(base))
                .getFailureReport()
                .getDetails();
    }

    private static void aceita(String caso, Function<RegrasDeArquitetura, ArchRule> regra) {
        String base = SINTETICO + caso;
        assertThatCode(() -> regra.apply(new RegrasDeArquitetura(base))
                .check(RegrasDeArquitetura.importarSinteticas(base)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Scenario: Dependência contra a direção das camadas é recusada")
    void direcaoDasCamadasRecusada() {
        List<String> violacoes = violacoes("a1", RegrasDeArquitetura::direcaoDasCamadas);

        assertThat(violacoes)
                .anySatisfy(v -> assertThat(v).contains(SINTETICO + "a1.domain.DominioQueUsaAplicacao")
                        .contains(SINTETICO + "a1.application.ServicoDeAplicacao"))
                .anySatisfy(v -> assertThat(v).contains(SINTETICO + "a1.application.AplicacaoQueUsaInfraestrutura")
                        .contains(SINTETICO + "a1.infrastructure.Adaptador"));
    }

    @Test
    @DisplayName("Scenario: Domínio dependente de framework é recusado")
    void dominioComFrameworkRecusado() {
        List<String> violacoes = violacoes("a2", RegrasDeArquitetura::dominioSemFramework);

        assertThat(violacoes)
                .anySatisfy(v -> assertThat(v).contains("a2.domain.DominioComSpring")
                        .contains("org.springframework.context.ApplicationContext"))
                .anySatisfy(v -> assertThat(v).contains("a2.domain.DominioComJpa")
                        .contains("jakarta.persistence.EntityManager"))
                .anySatisfy(v -> assertThat(v).contains("a2.domain.DominioComJackson")
                        .contains("com.fasterxml.jackson.databind.ObjectMapper"))
                .anySatisfy(v -> assertThat(v).contains("a2.domain.DominioComValidation")
                        .contains("jakarta.validation.constraints.NotNull"));
    }

    @Test
    @DisplayName("Scenario: Caso de uso com mais de uma operação pública é recusado")
    void casoDeUsoComDoisMetodosRecusado() {
        List<String> violacoes = violacoes("a3.negativo", RegrasDeArquitetura::umMetodoPublicoPorCasoDeUso);

        assertThat(violacoes).singleElement().asString()
                .contains(SINTETICO + "a3.negativo.application.DoisMetodosUseCase")
                .contains("[executar, outraOperacao]");
    }

    @Test
    @DisplayName("Construtor, estático, herdado, bridge e métodos de Object não contam para a A3")
    void metodosQueNaoContamSaoAceitos() {
        JavaClass completo = RegrasDeArquitetura.importarSinteticas(SINTETICO + "a3.positivo").get(CompletoUseCase.class);

        // A fonte sintetica precisa de fato ter cada forma que a regra manda ignorar.
        assertThat(completo.getConstructors()).anySatisfy(c -> assertThat(c.getModifiers()).contains(JavaModifier.PUBLIC));
        assertThat(completo.getMethods())
                .anySatisfy(m -> assertThat(m.getModifiers()).contains(JavaModifier.PUBLIC, JavaModifier.STATIC))
                .anySatisfy(m -> assertThat(m.getModifiers()).contains(JavaModifier.BRIDGE, JavaModifier.SYNTHETIC))
                .extracting(JavaMethod::getName).contains("equals", "hashCode", "toString");
        assertThat(completo.getAllMethods()).extracting(JavaMethod::getName).contains("herdado");
        assertThat(completo.getMethods()).extracting(JavaMethod::getName).doesNotContain("herdado");
        assertThat(completo.getMethods().stream().filter(RegrasDeArquitetura::contaParaCasoDeUso))
                .extracting(JavaMethod::getName).containsExactly("executar");

        aceita("a3.positivo", RegrasDeArquitetura::umMetodoPublicoPorCasoDeUso);
    }

    @Test
    @DisplayName("Scenario: Entidade persistente fora da área de persistência é recusada")
    void entidadeForaDePersistenciaRecusada() {
        List<String> violacoes = violacoes("a4", RegrasDeArquitetura::entidadesSoEmPersistencia);

        assertThat(violacoes).singleElement().asString()
                .contains(SINTETICO + "a4.domain.EntidadeForaDePersistencia");
    }

    @Test
    @DisplayName("Scenario: Controlador que contorna o caso de uso transacional é recusado")
    void controladorQueContornaOCasoDeUsoRecusado() {
        List<String> violacoes = violacoes("a5", RegrasDeArquitetura::controladoresPassamPeloCasoDeUsoTransacional);

        assertThat(violacoes)
                .anySatisfy(v -> assertThat(v).contains("a5.infrastructure.web.ControllerComRepositorio")
                        .contains("a5.infrastructure.dados.RepositorioSintetico"))
                .anySatisfy(v -> assertThat(v).contains("a5.infrastructure.web.ControllerComPortaDeSaida")
                        .contains("a5.domain.port.PortaDeSaidaSintetica"))
                .anySatisfy(v -> assertThat(v).contains("a5.infrastructure.web.ControllerComCasoDeUsoNu")
                        .contains("a5.application.QualquerUseCase"))
                .noneSatisfy(v -> assertThat(v).contains("ControllerConforme"));
    }

    @Test
    @DisplayName("Scenario: Emissão de token só é admitida no controlador de autenticação")
    void emissorDeTokenSoNaAutenticacao() {
        aceita("a6.positivo", RegrasDeArquitetura::emissorDeTokenSoNaAutenticacao);

        List<String> violacoes = violacoes("a6.negativo", RegrasDeArquitetura::emissorDeTokenSoNaAutenticacao);

        assertThat(violacoes).isNotEmpty()
                .allSatisfy(v -> assertThat(v).contains(SINTETICO + "a6.negativo.infrastructure.web.OutroControllerComJwt")
                        .contains(RegrasDeArquitetura.JWT_SERVICE))
                .noneSatisfy(v -> assertThat(v).contains("AutenticacaoController"));
    }

    @Test
    @DisplayName("Scenario: Escrita na saída padrão é recusada")
    void escritaNaSaidaPadraoRecusada() {
        List<String> violacoes = violacoes("a7", RegrasDeArquitetura::semSaidaPadrao);

        assertThat(violacoes).isNotEmpty()
                .allSatisfy(v -> assertThat(v).contains(SINTETICO + "a7.infrastructure.EscreveNaSaidaPadrao"))
                .noneSatisfy(v -> assertThat(v).contains("SemSaidaPadrao."));
    }
}
