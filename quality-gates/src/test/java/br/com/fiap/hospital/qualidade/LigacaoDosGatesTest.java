package br.com.fiap.hospital.qualidade;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

/**
 * Ligacao dos gates na fase verify do quality-gates (D3): relatorio-agregado, depois a auditoria,
 * depois o gate de cobertura; a auditoria recebe a raiz, a sessao e toda propriedade da tabela do D5.
 */
@DisplayName("Ligacao dos gates")
class LigacaoDosGatesTest {

    private final Element pom = PomsDoReactor.modulos().get(RegrasDoReactor.MODULO_TECNICO).getDocumentElement();
    private final Element plugins = XmlSeguro.caminho(pom, "build", "plugins");

    private List<Element> execucoes(String artifactId) {
        return XmlSeguro.filhos(XmlSeguro.filho(PomsDoReactor.plugin(plugins, artifactId), "executions"), "execution");
    }

    private Element execucao(String artifactId, String id) {
        return execucoes(artifactId).stream().filter(e -> id.equals(XmlSeguro.texto(e, "id"))).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("relatorio-agregado, auditoria-de-execucao e gate-de-cobertura, nessa ordem, na fase verify")
    void ordemDosGates() {
        List<String> ordemDosPlugins = XmlSeguro.filhos(plugins, "plugin").stream()
                .map(p -> XmlSeguro.texto(p, "artifactId")).toList();
        assertThat(ordemDosPlugins.indexOf("jacoco-maven-plugin")).isLessThan(ordemDosPlugins.indexOf("exec-maven-plugin"));
        assertThat(XmlSeguro.texto(execucao("jacoco-maven-plugin", "relatorio-agregado"), "phase")).isEqualTo("verify");
        assertThat(execucoes("exec-maven-plugin")).extracting(e -> XmlSeguro.texto(e, "id") + "@" + XmlSeguro.texto(e, "phase"))
                .containsExactly("auditoria-de-execucao@verify", "gate-de-cobertura@verify");
    }

    @Test
    @DisplayName("a auditoria recebe raiz, sessão, as propriedades da tabela do D5 e os canais do JUnit Platform, como literal não resolvido")
    void argumentosDaAuditoria() {
        List<String> argumentos = XmlSeguro.filhos(XmlSeguro.caminho(execucao("exec-maven-plugin", "auditoria-de-execucao"),
                "configuration", "arguments"), "argument").stream().map(a -> a.getTextContent().trim()).toList();
        assertThat(argumentos.subList(0, 5)).containsExactly("-cp", "${project.build.outputDirectory}",
                AuditoriaDeExecucao.class.getName(), "${project.basedir}/..", "${sessao.verificacao}");
        assertThat(argumentos.subList(5, argumentos.size())).containsExactlyElementsOf(java.util.stream.Stream.concat(
                PropriedadesDeFiltro.todas().stream(), ParametrosDoJUnitPlatform.CANAIS_DE_LINHA_DE_COMANDO.stream())
                .map(p -> p + "=${" + p + "}").toList());
    }
}
