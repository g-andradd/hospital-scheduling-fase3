package br.com.fiap.hospital.qualidade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * D3: o quality-gates agrega exatamente os cinco modulos de codigo e permanece estritamente
 * tecnico. Cada negativo muta uma copia dos POMs reais em um unico ponto.
 */
@DisplayName("Agregacao do reactor")
class AgregacaoDoReactorTest {

    private static final String GRUPO = "br.com.fiap.hospital";
    private static final String TECNICO = RegrasDoReactor.MODULO_TECNICO;

    private final Document raiz = PomsDoReactor.raiz();
    private final Map<String, Document> modulos = PomsDoReactor.modulos();

    private List<String> verificar() {
        return RegrasDoReactor.verificar(raiz.getDocumentElement(), PomsDoReactor.projetos(modulos));
    }

    private Element dependenciasDo(String modulo) {
        return XmlSeguro.filho(modulos.get(modulo).getDocumentElement(), "dependencies");
    }

    private static Element dependencia(Element dependencias, String grupo, String artefato, String escopo) {
        Element dependencia = PomsDoReactor.acrescentar(dependencias, "dependency");
        PomsDoReactor.acrescentar(dependencia, "groupId", grupo);
        PomsDoReactor.acrescentar(dependencia, "artifactId", artefato);
        if (escopo != null) {
            PomsDoReactor.acrescentar(dependencia, "scope", escopo);
        }
        return dependencia;
    }

    private Element internaDoTecnico(String artefato) {
        return XmlSeguro.filhos(dependenciasDo(TECNICO), "dependency").stream()
                .filter(d -> artefato.equals(XmlSeguro.texto(d, "artifactId")))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("os modulos internos saem do groupId e de <modules>: os cinco de codigo")
    void modulosInternosDetectados() {
        assertThat(XmlSeguro.texto(raiz.getDocumentElement(), "groupId")).isEqualTo(GRUPO);
        assertThat(RegrasDoReactor.modulos(raiz.getDocumentElement())).hasSize(6).contains(TECNICO);
        assertThat(RegrasDoReactor.modulosDeCodigo(raiz.getDocumentElement())).containsExactly(
                "shared-contracts", "shared-security",
                "agendamento-service", "notificacao-service", "historico-service");
    }

    @Test
    @DisplayName("Scenario: Módulo técnico permanece estritamente técnico — os POMs reais atendem")
    void pomsReaisAtendem() {
        assertThat(verificar()).isEmpty();
    }

    @Test
    @DisplayName("groupId escrito como ${project.groupId} continua interno")
    void groupIdPorPropriedadeContinuaInterno() {
        XmlSeguro.filho(internaDoTecnico("shared-security"), "groupId").setTextContent("${project.groupId}");
        assertThat(verificar()).isEmpty();
    }

    @Test
    @DisplayName("biblioteca externa em test é aceita e não entra na contagem")
    void externaEmTestNaoContaComoInterna() {
        dependencia(dependenciasDo(TECNICO), "org.example", "biblioteca-de-teste", "test");
        assertThat(verificar()).isEmpty();
    }

    @ParameterizedTest(name = "escopo {0}")
    @ValueSource(strings = {"test", "runtime", "provided", "system"})
    @DisplayName("dependência interna fora de compile é recusada")
    void internaForaDeCompileERecusada(String escopo) {
        PomsDoReactor.acrescentar(internaDoTecnico("agendamento-service"), "scope", escopo);
        assertThat(verificar()).containsExactly(
                "dependencia interna agendamento-service em escopo " + escopo + ": o agregado exige compile");
    }

    @Test
    @DisplayName("dependência interna com escopo compile explícito é aceita")
    void internaComCompileExplicitoEAceita() {
        PomsDoReactor.acrescentar(internaDoTecnico("historico-service"), "scope", "compile");
        assertThat(verificar()).isEmpty();
    }

    @Test
    @DisplayName("dependência interna com tipo diferente de jar é recusada")
    void internaComOutroTipoERecusada() {
        PomsDoReactor.acrescentar(internaDoTecnico("shared-contracts"), "type", "test-jar");
        assertThat(verificar()).containsExactly(
                "dependencia interna shared-contracts com tipo test-jar: o agregado exige jar");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"shared-contracts", "shared-security",
            "agendamento-service", "notificacao-service", "historico-service"})
    @DisplayName("Scenario: Módulo de código fora da agregação é recusado — interna faltando")
    void internaFaltandoERecusada(String modulo) {
        PomsDoReactor.remover(internaDoTecnico(modulo));
        assertThat(verificar()).containsExactly("dependencia interna faltando: " + modulo);
    }

    @Test
    @DisplayName("Scenario: Módulo de código fora da agregação é recusado — novo módulo em <modules>")
    void novoModuloForaDaAgregacaoERecusado() {
        Element lista = XmlSeguro.filho(raiz.getDocumentElement(), "modules");
        lista.insertBefore(raiz.createElement("module"), XmlSeguro.filhos(lista, "module").getLast())
                .setTextContent("prontuario-service");
        modulos.put("prontuario-service", PomsDoReactor.ler(
                PomsDoReactor.RAIZ.resolve("notificacao-service").resolve("pom.xml")));
        assertThat(verificar()).containsExactly("dependencia interna faltando: prontuario-service");
    }

    @Test
    @DisplayName("dependência interna sobrando (duplicada) é recusada")
    void internaDuplicadaERecusada() {
        dependencia(dependenciasDo(TECNICO), GRUPO, "notificacao-service", null);
        assertThat(verificar()).containsExactly("dependencia interna duplicada: notificacao-service");
    }

    @Test
    @DisplayName("o próprio quality-gates como dependência é recusado")
    void autodependenciaERecusada() {
        dependencia(dependenciasDo(TECNICO), GRUPO, TECNICO, null);
        assertThat(verificar()).containsExactly(TECNICO + " depende de si mesmo");
    }

    @Test
    @DisplayName("artefato do groupId do projeto fora de <modules> é recusado")
    void artefatoDoProjetoForaDosModulosERecusado() {
        dependencia(dependenciasDo(TECNICO), GRUPO, "modulo-fantasma", null);
        assertThat(verificar()).containsExactly(
                "dependencia do groupId do projeto fora de <modules>: modulo-fantasma");
    }

    @ParameterizedTest(name = "escopo {0}")
    @ValueSource(strings = {"", "compile", "runtime", "provided"})
    @DisplayName("biblioteca externa fora de test é recusada")
    void externaForaDeTestERecusada(String escopo) {
        dependencia(dependenciasDo(TECNICO), "org.example", "biblioteca", escopo.isEmpty() ? null : escopo);
        assertThat(verificar()).containsExactly("biblioteca externa org.example:biblioteca em escopo "
                + (escopo.isEmpty() ? "compile" : escopo) + ": fora de test, e o codigo principal usa so o JDK");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"shared-contracts", "shared-security",
            "agendamento-service", "notificacao-service", "historico-service"})
    @DisplayName("módulo de código dependendo do quality-gates é recusado")
    void moduloDeCodigoDependendoDoTecnicoERecusado(String modulo) {
        Element pom = modulos.get(modulo).getDocumentElement();
        dependencia(PomsDoReactor.garantir(pom, "dependencies"), GRUPO, TECNICO, "test");
        assertThat(verificar()).containsExactly("modulo de codigo " + modulo + " depende do " + TECNICO);
    }

    @Test
    @DisplayName("quality-gates fora de <modules> é recusado")
    void tecnicoForaDosModulosERecusado() {
        Element lista = XmlSeguro.filho(raiz.getDocumentElement(), "modules");
        PomsDoReactor.remover(XmlSeguro.filhos(lista, "module").getLast());
        assertThat(verificar()).containsExactly(TECNICO + " ausente de <modules>");
    }

    @Test
    @DisplayName("dependencyManagement da raiz rebaixando o escopo de um módulo interno é recusado")
    void escopoGerenciadoPelaRaizERecusado() {
        Element gerenciadas = XmlSeguro.caminho(raiz.getDocumentElement(), "dependencyManagement", "dependencies");
        dependencia(gerenciadas, GRUPO, "shared-security", "provided");
        assertThat(verificar()).containsExactly(
                "dependencyManagement da raiz fixa escopo provided para o modulo interno shared-security");
    }

    @Test
    @DisplayName("spring-boot-maven-plugin no quality-gates é recusado: seria aplicação executável")
    void pluginDeAplicacaoNoTecnicoERecusado() {
        Element plugins = XmlSeguro.caminho(modulos.get(TECNICO).getDocumentElement(), "build", "plugins");
        Element plugin = PomsDoReactor.acrescentar(plugins, "plugin");
        PomsDoReactor.acrescentar(plugin, "groupId", "org.springframework.boot");
        PomsDoReactor.acrescentar(plugin, "artifactId", "spring-boot-maven-plugin");
        assertThat(verificar()).containsExactly(
                TECNICO + " declara spring-boot-maven-plugin: seria aplicacao executavel");
    }

    @Test
    @DisplayName("fontes principais reais do quality-gates não têm Spring nem aplicação")
    void fontesReaisSemSpring() {
        assertThat(RegrasDoReactor.verificarFontes(Path.of("src", "main", "java"))).isEmpty();
    }

    @Test
    @DisplayName("fonte com @SpringBootApplication é recusada")
    void fonteComAplicacaoERecusada(@TempDir Path fontes) throws IOException {
        Files.writeString(fontes.resolve("Gates.java"),
                "package x;\n\n@SpringBootApplication\npublic class Gates {}\n");
        assertThat(RegrasDoReactor.verificarFontes(fontes))
                .containsExactly("fonte com @SpringBootApplication: Gates.java");
    }

    @Test
    @DisplayName("fonte importando Spring é recusada")
    void fonteComImportDoSpringERecusada(@TempDir Path fontes) throws IOException {
        Files.createDirectories(fontes.resolve("x"));
        Files.writeString(fontes.resolve("x").resolve("Gates.java"),
                "package x;\n\nimport org.springframework.util.Assert;\n\nclass Gates {}\n");
        assertThat(RegrasDoReactor.verificarFontes(fontes))
                .containsExactly("fonte importa Spring: x/Gates.java");
    }

    @Test
    @DisplayName("o nome citado em texto ou comentário não é confundido com a anotação")
    void citacaoNaoEConfundidaComAnotacao(@TempDir Path fontes) throws IOException {
        Files.writeString(fontes.resolve("Citacao.java"),
                "class Citacao {\n    // sem @SpringBootApplication aqui\n"
                        + "    String s = \"import org.springframework.x\";\n}\n");
        assertThat(RegrasDoReactor.verificarFontes(fontes)).isEmpty();
    }

    @Test
    @DisplayName("o classpath dos testes do quality-gates não carrega Spring")
    void classpathSemSpring() {
        assertThatThrownBy(() -> Class.forName("org.springframework.boot.SpringApplication"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("org.springframework.context.ApplicationContext"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
