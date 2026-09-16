package br.com.fiap.hospital.qualidade;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** A sessao de verificacao do POM raiz, e cada forma de ela parecer configurada sem estar. */
@DisplayName("Sessao de verificacao")
class SessaoDeVerificacaoTest {

    static final List<String> ALVOS = RegrasDaSessao.ALVOS;

    @Test
    @DisplayName("Scenario: Evidência residual é descartada antes dos testes — o POM raiz real atende")
    void pomRaizRealAtendeASessao() {
        assertThat(RegrasDaSessao.verificar(PomsDoReactor.raiz().getDocumentElement())).isEmpty();
    }

    @Test
    @DisplayName("execução presente só em pluginManagement é recusada")
    void execucaoSoEmPluginManagementERecusada() {
        Document pom = PomsDoReactor.raiz();
        Element antrun = antrun(pom);
        PomsDoReactor.remover(antrun);
        PomsDoReactor.elemento(pom, "build", "pluginManagement", "plugins").appendChild(antrun);
        assertThat(RegrasDaSessao.verificar(pom.getDocumentElement()))
                .containsExactly("sessao declarada so em pluginManagement: a execucao nunca roda");
    }

    @Test
    @DisplayName("inherited ausente é recusado")
    void inheritedAusenteERecusado() {
        Document pom = PomsDoReactor.raiz();
        PomsDoReactor.remover(XmlSeguro.filho(execucao(pom), "inherited"));
        assertThat(RegrasDaSessao.verificar(pom.getDocumentElement()))
                .containsExactly("execucao da sessao sem inherited=false: rodaria em cada modulo");
    }

    @Test
    @DisplayName("inherited=true é recusado")
    void inheritedVerdadeiroERecusado() {
        Document pom = PomsDoReactor.raiz();
        XmlSeguro.filho(execucao(pom), "inherited").setTextContent("true");
        assertThat(RegrasDaSessao.verificar(pom.getDocumentElement()))
                .containsExactly("execucao da sessao sem inherited=false: rodaria em cada modulo");
    }

    @Test
    @DisplayName("fase diferente de initialize é recusada")
    void faseErradaERecusada() {
        Document pom = PomsDoReactor.raiz();
        XmlSeguro.filho(execucao(pom), "phase").setTextContent("verify");
        assertThat(RegrasDaSessao.verificar(pom.getDocumentElement()))
                .containsExactly("execucao da sessao fora da fase initialize: verify");
    }

    @ParameterizedTest(name = "{0}")
    @FieldSource("ALVOS")
    @DisplayName("alvo de remoção faltando é recusado")
    void alvoFaltandoERecusado(String alvo) {
        Document pom = PomsDoReactor.raiz();
        XmlSeguro.filhos(fileset(pom), "include").stream()
                .filter(i -> alvo.equals(i.getAttribute("name")))
                .forEach(PomsDoReactor::remover);
        assertThat(RegrasDaSessao.verificar(pom.getDocumentElement()))
                .containsExactly("alvo de remocao faltando: " + alvo);
    }

    @Test
    @DisplayName("alvo de remoção além dos quatro do D4 é recusado")
    void alvoAlemDoD4ERecusado() {
        Document pom = PomsDoReactor.raiz();
        Element extra = pom.createElement("include");
        extra.setAttribute("name", "*/target/classes/**");
        fileset(pom).appendChild(extra);
        assertThat(RegrasDaSessao.verificar(pom.getDocumentElement()))
                .containsExactly("alvo de remocao fora do D4: */target/classes/**");
    }

    @Test
    @DisplayName("execução sem goal é recusada")
    void execucaoSemGoalERecusada() {
        Document pom = PomsDoReactor.raiz();
        PomsDoReactor.remover(XmlSeguro.filho(execucao(pom), "goals"));
        assertThat(RegrasDaSessao.verificar(pom.getDocumentElement()))
                .containsExactly("execucao da sessao sem goal run");
    }

    @Test
    @DisplayName("marcador gravado antes da remoção é recusado")
    void marcadorAntesDaRemocaoERecusado() {
        Document pom = PomsDoReactor.raiz();
        Element alvo = XmlSeguro.caminho(execucao(pom), "configuration", "target");
        alvo.insertBefore(XmlSeguro.filho(alvo, "echo"), XmlSeguro.filho(alvo, "delete"));
        assertThat(RegrasDaSessao.verificar(pom.getDocumentElement()))
                .containsExactly("marcador gravado antes da remocao das evidencias");
    }

    @Test
    @DisplayName("marcador que não grava a propriedade da sessão é recusado")
    void marcadorSemAPropriedadeERecusado() {
        Document pom = PomsDoReactor.raiz();
        XmlSeguro.filho(XmlSeguro.caminho(execucao(pom), "configuration", "target"), "echo")
                .setAttribute("message", "fixo");
        assertThat(RegrasDaSessao.verificar(pom.getDocumentElement()))
                .containsExactly("marcador nao grava a propriedade da sessao");
    }

    @Test
    @DisplayName("versão implícita do antrun é recusada")
    void versaoImplicitaERecusada() {
        Document pom = PomsDoReactor.raiz();
        PomsDoReactor.remover(XmlSeguro.filho(antrun(pom), "version"));
        assertThat(RegrasDaSessao.verificar(pom.getDocumentElement()))
                .containsExactly("maven-antrun-plugin sem versao explicita");
    }

    private static Element antrun(Document pom) {
        return PomsDoReactor.plugin(PomsDoReactor.elemento(pom, "build", "plugins"), "maven-antrun-plugin");
    }

    private static Element execucao(Document pom) {
        return XmlSeguro.caminho(antrun(pom), "executions", "execution");
    }

    private static Element fileset(Document pom) {
        return XmlSeguro.caminho(execucao(pom), "configuration", "target", "delete", "fileset");
    }
}
