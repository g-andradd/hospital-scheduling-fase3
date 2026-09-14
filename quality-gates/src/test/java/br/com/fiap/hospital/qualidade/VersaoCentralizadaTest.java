package br.com.fiap.hospital.qualidade;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A versao do reactor em um unico ponto, sem placeholder literal nos POMs gerados. */
@DisplayName("Versao centralizada")
class VersaoCentralizadaTest {

    private static final String REVISION = RegrasDeVersao.REVISION;
    private static final String PROJETO = RegrasDeVersao.VERSAO_DO_PROJETO;

    private String raiz = ler("pom.xml");
    private final Map<String, String> modulos = new LinkedHashMap<>();

    VersaoCentralizadaTest() {
        RegrasDoReactor.modulos(PomsDoReactor.raiz().getDocumentElement())
                .forEach(m -> modulos.put(m, ler(m + "/pom.xml")));
    }

    private static String ler(String relativo) {
        try {
            return Files.readString(PomsDoReactor.RAIZ.resolve(relativo), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<String> verificar() {
        return RegrasDeVersao.verificar(raiz, modulos);
    }

    private static int ocorrencias(String texto, String trecho) {
        return texto.split(java.util.regex.Pattern.quote(trecho), -1).length - 1;
    }

    @Test
    @DisplayName("Scenario: Alteração de versão em um único ponto — os POMs-fonte reais atendem")
    void pomsReaisAtendem() {
        assertThat(ocorrencias(raiz, REVISION)).isOne();
        assertThat(modulos).hasSize(6);
        modulos.forEach((modulo, texto) -> assertThat(ocorrencias(texto, REVISION)).as(modulo).isOne());
        assertThat(ocorrencias(modulos.get("quality-gates"), "<version>" + PROJETO + "</version>")).isEqualTo(5);
        assertThat(ocorrencias(raiz, RESOLVE)).isOne();
        assertThat(verificar()).isEmpty();
    }

    private static final String RESOLVE = "<dependencies>resolve</dependencies>";

    @Test
    @DisplayName("pomElements/dependencies=resolve removido é recusado")
    void resolveRemovidoERecusado() {
        raiz = raiz.replace(RESOLVE, "");
        assertThat(verificar()).containsExactly(
                "raiz: pomElements/dependencies do flatten-maven-plugin nao e resolve: null");
    }

    @Test
    @DisplayName("pomElements inteiro removido é recusado")
    void pomElementsRemovidoERecusado() {
        raiz = raiz.replace("<pomElements>", "").replace("</pomElements>", "").replace(RESOLVE, "");
        assertThat(verificar()).containsExactly(
                "raiz: pomElements/dependencies do flatten-maven-plugin nao e resolve: null");
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"interpolate", "keep", "expand", "flatten", "remove", ""})
    @DisplayName("resolve trocado por outro valor é recusado")
    void resolveTrocadoERecusado(String valor) {
        raiz = raiz.replace(RESOLVE, "<dependencies>" + valor + "</dependencies>");
        assertThat(verificar()).containsExactly(
                "raiz: pomElements/dependencies do flatten-maven-plugin nao e resolve: " + valor);
    }

    @Test
    @DisplayName("flattenMode diferente de resolveCiFriendliesOnly é recusado")
    void outroModoERecusado() {
        raiz = raiz.replace("<flattenMode>resolveCiFriendliesOnly</flattenMode>", "<flattenMode>oss</flattenMode>");
        assertThat(verificar()).containsExactly(
                "raiz: flattenMode do flatten-maven-plugin nao e resolveCiFriendliesOnly: oss");
    }

    @Test
    @DisplayName("plugin central movido para pluginManagement é recusado")
    void flattenSoEmPluginManagementERecusado() {
        int artefato = raiz.indexOf("<artifactId>flatten-maven-plugin</artifactId>");
        int de = raiz.lastIndexOf("<plugin>", artefato);
        int ate = raiz.indexOf("</plugin>", artefato) + "</plugin>".length();
        String plugin = raiz.substring(de, ate);
        raiz = raiz.substring(0, de) + raiz.substring(ate);
        int gestao = raiz.indexOf("<plugins>", raiz.indexOf("<pluginManagement>")) + "<plugins>".length();
        raiz = raiz.substring(0, gestao) + plugin + raiz.substring(gestao);
        assertThat(verificar()).containsExactly(
                "raiz: flatten-maven-plugin em pluginManagement: a configuracao central fica em build/plugins",
                "raiz: flatten-maven-plugin ausente de build/plugins");
    }

    @Test
    @DisplayName("execução sobrepondo a configuração central é recusada")
    void execucaoSobrepondoERecusada() {
        raiz = raiz.replace("<id>flatten</id>",
                "<id>flatten</id><configuration><pomElements><dependencies>keep</dependencies></pomElements></configuration>");
        assertThat(verificar()).containsExactly(
                "raiz: execucao flatten sobrepoe a configuracao central do flatten-maven-plugin");
    }

    @Test
    @DisplayName("módulo redeclarando o flatten é recusado")
    void moduloRedeclarandoERecusado() {
        modulos.put("shared-security", modulos.get("shared-security").replace("</project>",
                "<build><plugins><plugin><groupId>org.codehaus.mojo</groupId>"
                        + "<artifactId>flatten-maven-plugin</artifactId></plugin></plugins></build></project>"));
        assertThat(verificar()).containsExactly(
                "shared-security: redeclara o flatten-maven-plugin: a configuracao do POM publicado e central");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"agendamento-service", "notificacao-service", "historico-service", "quality-gates"})
    @DisplayName("dependência interna voltando a usar ${revision} é recusada")
    void dependenciaInternaComRevisionERecusada(String modulo) {
        String texto = modulos.get(modulo);
        modulos.put(modulo, texto.replaceFirst(java.util.regex.Pattern.quote("<version>" + PROJETO + "</version>"),
                java.util.regex.Matcher.quoteReplacement("<version>" + REVISION + "</version>")));
        assertThat(verificar()).containsExactly(
                modulo + ": " + REVISION + " deve aparecer exatamente uma vez, no parent, e aparece 2",
                modulo + ": dependencia shared-contracts usa " + REVISION + ": use " + PROJETO);
    }

    @Test
    @DisplayName("dependência interna com versão literal é recusada")
    void dependenciaInternaComVersaoLiteralERecusada() {
        String texto = modulos.get("quality-gates");
        modulos.put("quality-gates", texto.replaceFirst(java.util.regex.Pattern.quote("<version>" + PROJETO + "</version>"),
                "<version>1.0.0-SNAPSHOT</version>"));
        assertThat(verificar()).containsExactly(
                "quality-gates: dependencia interna shared-contracts com versao 1.0.0-SNAPSHOT: esperado " + PROJETO);
    }

    @Test
    @DisplayName("parent sem ${revision} é recusado")
    void parentSemRevisionERecusado() {
        modulos.put("shared-security", modulos.get("shared-security").replace(REVISION, "1.0.0-SNAPSHOT"));
        assertThat(verificar()).containsExactly(
                "shared-security: " + REVISION + " deve aparecer exatamente uma vez, no parent, e aparece 0",
                "shared-security: versao do parent nao e " + REVISION);
    }

    @Test
    @DisplayName("projeto raiz sem ${revision} é recusado")
    void raizSemRevisionERecusada() {
        raiz = raiz.replace("<version>" + REVISION + "</version>", "<version>1.0.0-SNAPSHOT</version>");
        assertThat(verificar()).containsExactly(
                "raiz: " + REVISION + " deve aparecer exatamente uma vez, e aparece 0",
                "raiz: versao do projeto nao e " + REVISION);
    }

    @Test
    @DisplayName("${revision} a mais, mesmo em comentário ou propriedade, é recusado")
    void ocorrenciaAMaisERecusada() {
        modulos.put("historico-service", modulos.get("historico-service")
                .replace("<artifactId>historico-service</artifactId>",
                        "<artifactId>historico-service</artifactId><!-- " + REVISION + " -->"));
        raiz = raiz.replace("</properties>", "<outra>" + REVISION + "</outra></properties>");
        assertThat(verificar()).containsExactly(
                "raiz: " + REVISION + " deve aparecer exatamente uma vez, e aparece 2",
                "historico-service: " + REVISION + " deve aparecer exatamente uma vez, no parent, e aparece 2");
    }
}
