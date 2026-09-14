package br.com.fiap.hospital.qualidade;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** A unica exclusao de cobertura admitida, e cada forma de tirar codigo da conta. */
@DisplayName("Exclusoes de cobertura")
class ExclusoesDeCoberturaTest {

    private static final String NORMATIVA = RegrasDeExclusao.NORMATIVA;
    private static final String AUSENTE = "exclusao normativa ausente: " + NORMATIVA;

    private final Document raiz = PomsDoReactor.raiz();
    private final Map<String, Document> modulos = PomsDoReactor.modulos();

    private List<String> verificar() {
        return RegrasDeExclusao.verificar(raiz.getDocumentElement(), PomsDoReactor.projetos(modulos));
    }

    private Element jacocoDaRaiz() {
        return PomsDoReactor.plugin(
                PomsDoReactor.elemento(raiz, "build", "plugins"), "jacoco-maven-plugin");
    }

    private Element excludesDaRaiz() {
        return XmlSeguro.caminho(jacocoDaRaiz(), "configuration", "excludes");
    }

    @Test
    @DisplayName("Scenario: Exclusão adicional de cobertura é recusada — o POM real tem só a normativa")
    void pomsReaisTemSoANormativa() {
        assertThat(XmlSeguro.filhos(excludesDaRaiz(), "exclude"))
                .extracting(e -> e.getTextContent().trim()).containsExactly(NORMATIVA);
        assertThat(verificar()).isEmpty();
    }

    @Test
    @DisplayName("exclusão adicional é recusada")
    void exclusaoAdicionalERecusada() {
        PomsDoReactor.acrescentar(excludesDaRaiz(), "exclude", "**/*Config.class");
        assertThat(verificar()).containsExactly(
                "exclusao alem de " + NORMATIVA + " em raiz: **/*Config.class");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"br/com/fiap/hospital/agendamento/domain/**",
            "br/com/fiap/hospital/agendamento/application/*", "**/infrastructure/**"})
    @DisplayName("exclusão por pacote é recusada")
    void exclusaoPorPacoteERecusada(String pacote) {
        PomsDoReactor.acrescentar(excludesDaRaiz(), "exclude", pacote);
        assertThat(verificar()).containsExactly("exclusao alem de " + NORMATIVA + " em raiz: " + pacote);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"**/*Application*", "**/*Application*.class", "**/*.class", "**/*"})
    @DisplayName("variante mais ampla no lugar da normativa é recusada")
    void varianteMaisAmplaERecusada(String variante) {
        XmlSeguro.filho(excludesDaRaiz(), "exclude").setTextContent(variante);
        assertThat(verificar()).containsExactly(
                "exclusao alem de " + NORMATIVA + " em raiz: " + variante, AUSENTE);
    }

    @Test
    @DisplayName("exclusão vazia é recusada")
    void exclusaoVaziaERecusada() {
        PomsDoReactor.acrescentar(excludesDaRaiz(), "exclude", "");
        assertThat(verificar()).containsExactly("exclusao vazia em raiz");
    }

    @Test
    @DisplayName("lista de exclusões vazia é recusada")
    void listaVaziaERecusada() {
        PomsDoReactor.remover(XmlSeguro.filho(excludesDaRaiz(), "exclude"));
        assertThat(verificar()).containsExactly("exclusao vazia em raiz", AUSENTE);
    }

    @Test
    @DisplayName("ausência da normativa é recusada")
    void ausenciaDaNormativaERecusada() {
        PomsDoReactor.remover(excludesDaRaiz());
        assertThat(verificar()).containsExactly(AUSENTE);
    }

    @Test
    @DisplayName("normativa repetida na raiz é recusada")
    void normativaRepetidaERecusada() {
        PomsDoReactor.acrescentar(excludesDaRaiz(), "exclude", NORMATIVA);
        assertThat(verificar()).containsExactly("exclusao normativa repetida na raiz: 2 vezes");
    }

    @Test
    @DisplayName("exclusão na configuração de uma execução é recusada")
    void exclusaoEmExecucaoERecusada() {
        Element execucao = XmlSeguro.filhos(XmlSeguro.filho(jacocoDaRaiz(), "executions"), "execution").stream()
                .filter(e -> "report".equals(XmlSeguro.texto(e, "id"))).findFirst().orElseThrow();
        Element excludes = PomsDoReactor.acrescentar(PomsDoReactor.garantir(execucao, "configuration"), "excludes");
        PomsDoReactor.acrescentar(excludes, "exclude", "**/dto/**");
        assertThat(verificar()).containsExactly(
                "exclusao alem de " + NORMATIVA + " em raiz (execucao report): **/dto/**");
    }

    @Test
    @DisplayName("inclusão restritiva é recusada")
    void inclusaoRestritivaERecusada() {
        Element includes = PomsDoReactor.acrescentar(XmlSeguro.filho(jacocoDaRaiz(), "configuration"), "includes");
        PomsDoReactor.acrescentar(includes, "include", "br/com/fiap/hospital/agendamento/domain/**");
        assertThat(verificar()).containsExactly(
                "inclusao restritiva em raiz: br/com/fiap/hospital/agendamento/domain/**");
    }

    @Test
    @DisplayName("exclusão declarada em um módulo, mesmo a normativa, é recusada")
    void exclusaoEmModuloERecusada() {
        Element pom = modulos.get("agendamento-service").getDocumentElement();
        Element plugins = PomsDoReactor.garantir(PomsDoReactor.garantir(pom, "build"), "plugins");
        Element plugin = PomsDoReactor.acrescentar(plugins, "plugin");
        PomsDoReactor.acrescentar(plugin, "groupId", "org.jacoco");
        PomsDoReactor.acrescentar(plugin, "artifactId", "jacoco-maven-plugin");
        Element excludes = PomsDoReactor.acrescentar(PomsDoReactor.acrescentar(plugin, "configuration"), "excludes");
        PomsDoReactor.acrescentar(excludes, "exclude", NORMATIVA);
        PomsDoReactor.acrescentar(excludes, "exclude", "**/web/**");
        assertThat(verificar()).containsExactly(
                "exclusao " + NORMATIVA + " repetida fora de build/plugins da raiz em agendamento-service",
                "exclusao alem de " + NORMATIVA + " em agendamento-service: **/web/**");
    }

    @Test
    @DisplayName("exclusão em pluginManagement da raiz é recusada")
    void exclusaoEmPluginManagementERecusada() {
        Element plugins = PomsDoReactor.elemento(raiz, "build", "pluginManagement", "plugins");
        Element plugin = PomsDoReactor.acrescentar(plugins, "plugin");
        PomsDoReactor.acrescentar(plugin, "groupId", "org.jacoco");
        PomsDoReactor.acrescentar(plugin, "artifactId", "jacoco-maven-plugin");
        Element excludes = PomsDoReactor.acrescentar(PomsDoReactor.acrescentar(plugin, "configuration"), "excludes");
        PomsDoReactor.acrescentar(excludes, "exclude", "**/*Properties.class");
        assertThat(verificar()).containsExactly(
                "exclusao alem de " + NORMATIVA + " em raiz (pluginManagement): **/*Properties.class");
    }
}
