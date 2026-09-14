package br.com.fiap.hospital.qualidade;

import static br.com.fiap.hospital.qualidade.ReactorDeAuditoria.ADAPTER;
import static br.com.fiap.hospital.qualidade.ReactorDeAuditoria.CONTRATO;
import static br.com.fiap.hospital.qualidade.ReactorDeAuditoria.FABRICA;
import static br.com.fiap.hospital.qualidade.ReactorDeAuditoria.FAKE;
import static br.com.fiap.hospital.qualidade.ReactorDeAuditoria.GRAPHIQL;
import static br.com.fiap.hospital.qualidade.ReactorDeAuditoria.JWT;
import static br.com.fiap.hospital.qualidade.ReactorDeAuditoria.PARAMETRIZADA;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.qualidade.InventarioDeTestes.Plugin;
import br.com.fiap.hospital.qualidade.InventarioDeTestes.Suite;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A auditoria da execucao integral (D5) sobre um reactor sintetico modelado nas estruturas reais.
 * Cada negativo tem uma unica causa, com todos os demais relatorios presentes.
 */
@DisplayName("Auditoria de execucao")
class AuditoriaDeExecucaoTest {

    @TempDir
    Path raiz;

    ReactorDeAuditoria reactor;

    @BeforeEach
    void montar() {
        reactor = ReactorDeAuditoria.padrao(raiz);
    }

    private List<String> violacoes() {
        return reactor.auditar().violacoes();
    }

    private static final String AG_SUREFIRE = "agendamento/surefire-reports";
    private static final String AG_FAILSAFE = "agendamento/failsafe-reports";

    @Nested
    @DisplayName("aceitacao")
    class Aceitacao {

        @Test
        @DisplayName("Scenario: Execução integral e verde é aceita — por módulo, suítes, relatórios e casos")
        void execucaoIntegralEVerdeEAceita() {
            AuditoriaDeExecucao.Resultado resultado = reactor.auditar();
            assertThat(resultado.violacoes()).isEmpty();
            assertThat(resultado.suites()).extracting(Suite::nome, Suite::plugin).containsExactly(
                    org.assertj.core.groups.Tuple.tuple(FAKE, Plugin.SUREFIRE),
                    org.assertj.core.groups.Tuple.tuple(FABRICA, Plugin.SUREFIRE),
                    org.assertj.core.groups.Tuple.tuple(PARAMETRIZADA, Plugin.SUREFIRE),
                    org.assertj.core.groups.Tuple.tuple(ADAPTER, Plugin.FAILSAFE),
                    org.assertj.core.groups.Tuple.tuple(GRAPHIQL, Plugin.FAILSAFE),
                    org.assertj.core.groups.Tuple.tuple(JWT, Plugin.SUREFIRE));
            assertThat(resultado.totais()).extracting(t -> t.modulo() + " " + t.suitesSurefire() + "/" + t.suitesFailsafe()
                    + " relatorios=" + t.relatorios() + " casos=" + t.casos())
                    .containsExactly("agendamento 3/2 relatorios=14 casos=22", "seguranca 1/0 relatorios=4 casos=5");
            assertThat(resultado.tabela()).hasSize(4);
            assertThat(resultado.tabela().getLast()).contains("total", "4", "2", "18", "27");
        }

        @Test
        @DisplayName("ConsultaRepositoryAdapterIT: três nested herdadas do contrato, externo zero e família positiva")
        void adapterComNestedHerdadasEExternoZero() {
            Suite adapter = suite(ADAPTER);
            assertThat(adapter.plugin()).isEqualTo(Plugin.FAILSAFE);
            assertThat(adapter.obrigatorios()).containsExactly(ADAPTER, CONTRATO + "$Listagem",
                    CONTRATO + "$GravacaoERecuperacao", CONTRATO + "$DeteccaoDeConflito");
            assertThat(reactor.ler("agendamento/target/failsafe-reports/TEST-" + ADAPTER + ".xml")).contains("tests=\"0\"");
        }

        @Test
        @DisplayName("ConsultaRepositoryFakeTest no Surefire e ConsultaRepositoryAdapterIT no Failsafe não são ambíguos")
        void fakeEAdapterEmPluginsDistintos() {
            assertThat(suite(FAKE).plugin()).isEqualTo(Plugin.SUREFIRE);
            assertThat(suite(FAKE).familia()).isEqualTo(java.util.Set.of(FAKE, CONTRATO + "$Listagem",
                    CONTRATO + "$GravacaoERecuperacao", CONTRATO + "$DeteccaoDeConflito"));
            assertThat(violacoes()).isEmpty();
        }

        @Test
        @DisplayName("GraphiqlPorProfileIT e JwtServiceTest: nested declaradas com relatório próprio")
        void graphiqlEJwtComNestedDeclaradas() {
            assertThat(suite(GRAPHIQL).obrigatorios()).containsExactly(GRAPHIQL, GRAPHIQL + "$ProfilePadrao",
                    GRAPHIQL + "$ProfileDev", GRAPHIQL + "$ProfileDemo");
            assertThat(suite(JWT).obrigatorios()).containsExactly(JWT, JWT + "$Emissao", JWT + "$Recusa", JWT + "$Configuracao");
        }

        @Test
        @DisplayName("Scenario: Abstratas, bases, contratos herdados e auxiliares não contam como omissões")
        void abstratasBasesEAuxiliaresNaoExigidas() {
            AuditoriaDeExecucao.Resultado resultado = reactor.auditar();
            assertThat(resultado.violacoes()).isEmpty();
            assertThat(resultado.suites()).extracting(Suite::nome).doesNotContain(CONTRATO,
                    "br.com.fiap.hospital.agendamento.contrato.BaseAbstrataTest",
                    "br.com.fiap.hospital.agendamento.integracao.M05JpaBase",
                    "br.com.fiap.hospital.agendamento.fake.ConsultaRepositoryFake");
            assertThat(InventarioDeTestes.inventariar(raiz, List.of("agendamento", "seguranca"),
                    java.util.Map.of(Plugin.SUREFIRE, "Test", Plugin.FAILSAFE, "IT")).classificacao().get("agendamento"))
                    .containsEntry("abstrata", 3)
                    .containsEntry("auxiliar", 1)
                    .containsEntry("nao suite (interface, enum, record, anotacao)", 4)
                    .containsEntry("suite surefire", 3)
                    .containsEntry("suite failsafe", 2);
        }

        @Test
        @DisplayName("failsafe-summary.xml válido, *.txt, *-output.txt e dumpstream não são relatórios nem órfãos")
        void sumarioEArquivosDosPluginsIgnorados() {
            reactor.escrever("seguranca/target/surefire-reports/TEST-" + JWT + ".txt", "nao e xml");
            assertThat(violacoes()).isEmpty();
        }

        private Suite suite(String nome) {
            return reactor.auditar().suites().stream().filter(s -> s.nome().equals(nome)).findFirst().orElseThrow();
        }
    }

    @Nested
    @DisplayName("famílias de relatórios")
    class Familias {

        @Test
        @DisplayName("Scenario: Suíte omitida é recusada")
        void suiteOmitida() {
            reactor.apagar(reactor.relatorioDe("agendamento", Plugin.SUREFIRE, PARAMETRIZADA));
            assertThat(violacoes()).containsExactly(
                    "suite omitida: agendamento: " + PARAMETRIZADA + " sem nenhum relatorio da familia em surefire-reports");
        }

        @Test
        @DisplayName("nested herdada sem relatório é recusada")
        void nestedHerdadaSemRelatorio() {
            reactor.apagar(reactor.relatorioDe("agendamento", Plugin.FAILSAFE, CONTRATO + "$Listagem"));
            assertThat(violacoes()).containsExactly("classe aninhada sem relatorio: " + AG_FAILSAFE + ": TEST-"
                    + CONTRATO + "$Listagem.xml na familia de " + ADAPTER);
        }

        @Test
        @DisplayName("nested declarada sem relatório é recusada")
        void nestedDeclaradaSemRelatorio() {
            reactor.apagar(reactor.relatorioDe("seguranca", Plugin.SUREFIRE, JWT + "$Recusa"));
            assertThat(violacoes()).containsExactly("classe aninhada sem relatorio: seguranca/surefire-reports: TEST-"
                    + JWT + "$Recusa.xml na familia de " + JWT);
        }

        @Test
        @DisplayName("relatório externo ausente é recusado mesmo com as nested presentes")
        void externoAusente() {
            reactor.apagar(reactor.relatorioDe("agendamento", Plugin.FAILSAFE, ADAPTER));
            assertThat(violacoes()).containsExactly("relatorio externo ausente: " + AG_FAILSAFE + ": TEST-" + ADAPTER
                    + ".xml da suite " + ADAPTER);
        }

        @Test
        @DisplayName("Scenario: Família sem casos executados é recusada")
        void familiaZero() {
            reactor.relatorio("agendamento", Plugin.FAILSAFE, CONTRATO + "$Listagem", 0);
            reactor.relatorio("agendamento", Plugin.FAILSAFE, CONTRATO + "$GravacaoERecuperacao", 0);
            reactor.relatorio("agendamento", Plugin.FAILSAFE, CONTRATO + "$DeteccaoDeConflito", 0);
            assertThat(violacoes()).containsExactly("familia sem casos executados: " + AG_FAILSAFE + ": " + ADAPTER + " tem total zero");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"failures", "errors", "skipped"})
        @DisplayName("Scenario: Teste falho, com erro ou pulado é recusado — no atributo")
        void insucessoNoAtributo(String atributo) {
            String atributos = "tests=\"6\" errors=\"0\" skipped=\"0\" failures=\"0\"".replace(atributo + "=\"0\"", atributo + "=\"1\"");
            reactor.relatorioCom("agendamento", Plugin.SUREFIRE, PARAMETRIZADA, 6, atributos, "");
            assertThat(violacoes()).containsExactly("relatorio com " + atributo + "=1: " + AG_SUREFIRE + "/TEST-" + PARAMETRIZADA + ".xml");
        }

        @ParameterizedTest(name = "<{0}>")
        @FieldSource("br.com.fiap.hospital.qualidade.FamiliasDeRelatorios#ELEMENTOS_DE_INSUCESSO")
        @DisplayName("Scenario: Teste falho, com erro ou pulado é recusado — no caso, identificando suíte e caso")
        void insucessoNoCaso(String elemento) {
            reactor.relatorioCom("seguranca", Plugin.SUREFIRE, JWT + "$Emissao", 2,
                    "tests=\"2\" errors=\"0\" skipped=\"0\" failures=\"0\"", "<" + elemento + " message=\"x\"/>");
            assertThat(violacoes()).containsExactly("caso com <" + elemento + ">: seguranca/surefire-reports/TEST-" + JWT
                    + "$Emissao.xml: " + JWT + "$Emissao#caso0");
        }

        @Test
        @DisplayName("Scenario: Relatório ilegível é recusado")
        void relatorioIlegivel() {
            reactor.escrever("agendamento/target/surefire-reports/TEST-" + FABRICA + ".xml", "<testsuite name=\"x\"");
            List<String> violacoes = violacoes();
            assertThat(violacoes).hasSize(1);
            assertThat(violacoes.getFirst()).startsWith("relatorio ilegivel: " + AG_SUREFIRE + "/TEST-" + FABRICA + ".xml: documento ilegivel");
        }

        @Test
        @DisplayName("relatório com contagem incoerente é recusado")
        void relatorioIncoerente() {
            reactor.relatorioCom("agendamento", Plugin.SUREFIRE, FABRICA, 2, "tests=\"3\" errors=\"0\" skipped=\"0\" failures=\"0\"", "");
            assertThat(violacoes()).containsExactly("relatorio incoerente: " + AG_SUREFIRE + "/TEST-" + FABRICA + ".xml: tests=3 e 2 testcase");
        }

        @Test
        @DisplayName("Scenario: Relatório órfão ou atribuição ambígua é recusada — órfão")
        void relatorioOrfao() {
            reactor.relatorio("agendamento", Plugin.FAILSAFE, "br.com.fiap.hospital.agendamento.integracao.RemovidaIT", 3);
            assertThat(violacoes()).containsExactly("relatorio orfao: " + AG_FAILSAFE
                    + "/TEST-br.com.fiap.hospital.agendamento.integracao.RemovidaIT.xml: nenhuma suite executavel tem essa familia");
        }

        @Test
        @DisplayName("nome que só começa como o de uma suíte não é associado a ela")
        void semAssociacaoPorPrefixo() {
            reactor.relatorio("agendamento", Plugin.SUREFIRE, FAKE + "Extra", 1);
            reactor.relatorio("agendamento", Plugin.SUREFIRE, CONTRATO + "$ListagemAntiga", 1);
            assertThat(violacoes()).containsExactly(
                    "relatorio orfao: " + AG_SUREFIRE + "/TEST-" + CONTRATO + "$ListagemAntiga.xml: nenhuma suite executavel tem essa familia",
                    "relatorio orfao: " + AG_SUREFIRE + "/TEST-" + FAKE + "Extra.xml: nenhuma suite executavel tem essa familia");
        }

        @Test
        @DisplayName("Scenario: Relatório órfão ou atribuição ambígua é recusada — duas subclasses do contrato no mesmo plugin")
        void relatorioAmbiguo() {
            reactor.escrever(FontesDeAuditoria.AG + "contrato/ConsultaRepositoryMemoriaTest.java", """
                    package br.com.fiap.hospital.agendamento.contrato;

                    class ConsultaRepositoryMemoriaTest extends ConsultaRepositoryContractTest {
                        @Override
                        protected Object criarRepositorio() { return new Object(); }
                    }
                    """);
            String memoria = "br.com.fiap.hospital.agendamento.contrato.ConsultaRepositoryMemoriaTest";
            reactor.relatorio("agendamento", Plugin.SUREFIRE, memoria, 0);
            String donos = "[" + FAKE + ", " + memoria + "]";
            assertThat(violacoes()).containsExactly(
                    "relatorio ambiguo: " + AG_SUREFIRE + "/TEST-" + CONTRATO + "$DeteccaoDeConflito.xml: atribuivel as familias de " + donos,
                    "relatorio ambiguo: " + AG_SUREFIRE + "/TEST-" + CONTRATO + "$GravacaoERecuperacao.xml: atribuivel as familias de " + donos,
                    "relatorio ambiguo: " + AG_SUREFIRE + "/TEST-" + CONTRATO + "$Listagem.xml: atribuivel as familias de " + donos);
        }

        @Test
        @DisplayName("Scenario: Módulo sem relatórios é recusado — sem diretório")
        void moduloSemDiretorio() {
            reactor.apagar(reactor.diretorio("seguranca", Plugin.SUREFIRE));
            assertThat(violacoes()).containsExactly("modulo seguranca sem relatorios: 1 suites surefire e nenhum diretorio surefire-reports");
        }

        @Test
        @DisplayName("Scenario: Módulo sem relatórios é recusado — diretório só com arquivos que não são relatórios")
        void moduloSemTestXml() {
            for (String nome : List.of(JWT, JWT + "$Emissao", JWT + "$Recusa", JWT + "$Configuracao")) {
                reactor.apagar(reactor.relatorioDe("seguranca", Plugin.SUREFIRE, nome));
            }
            assertThat(violacoes()).containsExactly("modulo seguranca sem relatorios: 1 suites surefire e nenhum TEST-*.xml em surefire-reports");
        }

        @Test
        @DisplayName("failsafe-summary.xml com resultado falho é recusado")
        void sumarioFalho() {
            reactor.sumario("agendamento", "255", 8, 0, 1, 0);
            assertThat(violacoes()).containsExactly("failsafe-summary.xml registra resultado falho: agendamento/failsafe-reports/"
                    + "failsafe-summary.xml: result=255, completed=8, errors=0, failures=1, skipped=0, timeout=false");
        }

        @Test
        @DisplayName("failsafe-summary.xml ausente em módulo com IT é recusado")
        void sumarioAusente() {
            reactor.apagar(reactor.diretorio("agendamento", Plugin.FAILSAFE).resolve(FamiliasDeRelatorios.SUMARIO));
            assertThat(violacoes()).containsExactly("failsafe-summary.xml ausente: agendamento/failsafe-reports/failsafe-summary.xml");
        }

        @Test
        @DisplayName("failsafe-summary.xml ilegível é recusado e nunca é órfão")
        void sumarioIlegivel() {
            reactor.escrever("agendamento/target/failsafe-reports/failsafe-summary.xml", "<failsafe-summary>");
            List<String> violacoes = violacoes();
            assertThat(violacoes).hasSize(1);
            assertThat(violacoes.getFirst()).startsWith("failsafe-summary.xml ilegivel: agendamento/failsafe-reports/failsafe-summary.xml");
        }
    }

    @Nested
    @DisplayName("inventário e classificação")
    class Inventario {

        private static final String DOMINIO = FontesDeAuditoria.AG + "dominio/";

        @Test
        @DisplayName("Scenario: Teste concreto fora da convenção é recusado")
        void foraDaConvencao() {
            reactor.escrever(DOMINIO + "VerificacaoDoPeriodo.java", """
                    package br.com.fiap.hospital.agendamento.dominio;

                    import org.junit.jupiter.api.Test;

                    class VerificacaoDoPeriodo {
                        @Test void verifica() { }
                    }
                    """);
            assertThat(violacoes()).containsExactly("teste fora da convencao: agendamento: br.com.fiap.hospital.agendamento.dominio"
                    + ".VerificacaoDoPeriodo (" + DOMINIO + "VerificacaoDoPeriodo.java) declara ou herda testes e nao termina em Test"
                    + " nem IT: nunca seria executado");
        }

        @Test
        @DisplayName("superclasse não resolvida falha fechado com arquivo e símbolo")
        void superclasseNaoResolvida() {
            reactor.escrever(DOMINIO + "SemBaseTest.java", """
                    package br.com.fiap.hospital.agendamento.dominio;

                    class SemBaseTest extends BaseQueNaoExiste { }
                    """);
            assertThat(violacoes()).containsExactly("supertipo nao resolvido: " + DOMINIO + "SemBaseTest.java: "
                    + "br.com.fiap.hospital.agendamento.dominio.SemBaseTest estende BaseQueNaoExiste (sem import, fora do pacote e de java.lang)");
        }

        @Test
        @DisplayName("anotação de teste não resolvida falha fechado com arquivo e símbolo")
        void anotacaoNaoResolvida() {
            reactor.escrever(DOMINIO + "SemImportTest.java", """
                    package br.com.fiap.hospital.agendamento.dominio;

                    class SemImportTest {
                        @Test void semImport() { }
                    }
                    """);
            assertThat(violacoes()).containsExactly(
                    "anotacao nao resolvida: " + DOMINIO + "SemImportTest.java:4: @Test (sem import que a identifique)");
        }

        @Test
        @DisplayName("fonte de teste não interpretável falha fechado")
        void fonteNaoInterpretavel() {
            reactor.escrever(DOMINIO + "QuebradoTest.java", "package br.com.fiap.hospital.agendamento.dominio;\nclass QuebradoTest {\n");
            List<String> violacoes = violacoes();
            assertThat(violacoes).hasSize(1);
            assertThat(violacoes.getFirst()).startsWith("fonte de teste nao interpretavel: " + DOMINIO + "QuebradoTest.java:");
        }

        @Test
        @DisplayName("classe aninhada com testes sem @Nested nunca executa e é recusada")
        void aninhadaSemNested() {
            reactor.substituir(FontesDeAuditoria.SEG + "JwtServiceTest.java", "    @Nested\n    class Configuracao {",
                    "    static class Configuracao {");
            assertThat(violacoes()).containsExactly("classe aninhada com testes sem @Nested nunca executa: " + FontesDeAuditoria.SEG
                    + "JwtServiceTest.java: " + JWT.replace('$', '.') + ".Configuracao",
                    "relatorio orfao: seguranca/surefire-reports/TEST-" + JWT + "$Configuracao.xml: nenhuma suite executavel tem essa familia");
        }

        @Test
        @DisplayName("nested herdada transitivamente por base abstrata intermediária continua exigida")
        void nestedHerdadaTransitivamente() {
            reactor.escrever(FontesDeAuditoria.AG + "integracao/AdaptadorIntermediario.java", """
                    package br.com.fiap.hospital.agendamento.integracao;

                    import br.com.fiap.hospital.agendamento.contrato.ConsultaRepositoryContractTest;

                    abstract class AdaptadorIntermediario extends ConsultaRepositoryContractTest { }
                    """);
            reactor.substituir(FontesDeAuditoria.AG + "integracao/ConsultaRepositoryAdapterIT.java",
                    "extends ConsultaRepositoryContractTest", "extends AdaptadorIntermediario");
            assertThat(violacoes()).isEmpty();
            reactor.apagar(reactor.relatorioDe("agendamento", Plugin.FAILSAFE, CONTRATO + "$DeteccaoDeConflito"));
            assertThat(violacoes()).containsExactly("classe aninhada sem relatorio: " + AG_FAILSAFE + ": TEST-" + CONTRATO
                    + "$DeteccaoDeConflito.xml na familia de " + ADAPTER);
        }

        @Test
        @DisplayName("teste default herdado de interface torna a classe concreta uma suíte")
        void testeHerdadoDeInterface() {
            reactor.escrever(DOMINIO + "ContratoDePorta.java", """
                    package br.com.fiap.hospital.agendamento.dominio;

                    import org.junit.jupiter.api.Test;

                    interface ContratoDePorta {
                        @Test default void respeitaContrato() { }
                    }
                    """);
            reactor.escrever(DOMINIO + "PortaEmMemoriaTest.java", """
                    package br.com.fiap.hospital.agendamento.dominio;

                    class PortaEmMemoriaTest implements ContratoDePorta { }
                    """);
            assertThat(violacoes()).containsExactly("suite omitida: agendamento: br.com.fiap.hospital.agendamento.dominio"
                    + ".PortaEmMemoriaTest sem nenhum relatorio da familia em surefire-reports");
        }

        @Test
        @DisplayName("parametrizados, repeated, template e factory são métodos de teste; meta-anotação também")
        void todasAsFormasDeMetodoDeTeste() {
            reactor.escrever(DOMINIO + "MeuTeste.java", """
                    package br.com.fiap.hospital.agendamento.dominio;

                    import java.lang.annotation.Retention;
                    import java.lang.annotation.RetentionPolicy;
                    import org.junit.jupiter.api.Test;

                    @Test
                    @Retention(RetentionPolicy.RUNTIME)
                    @interface MeuTeste { }
                    """);
            List<String> formas = List.of("@org.junit.jupiter.api.RepeatedTest(2)", "@org.junit.jupiter.api.TestTemplate",
                    "@MeuTeste", "@org.junit.jupiter.api.TestFactory", "@org.junit.jupiter.params.ParameterizedTest");
            List<String> esperadas = new ArrayList<>();
            for (int i = 0; i < formas.size(); i++) {
                reactor.escrever(DOMINIO + "Forma" + i + "Test.java", "package br.com.fiap.hospital.agendamento.dominio;\n"
                        + "class Forma" + i + "Test {\n    " + formas.get(i) + " void metodo() { }\n}\n");
                esperadas.add("suite omitida: agendamento: br.com.fiap.hospital.agendamento.dominio.Forma" + i
                        + "Test sem nenhum relatorio da familia em surefire-reports");
            }
            assertThat(violacoes()).containsExactlyElementsOf(esperadas);
        }

        @Test
        @DisplayName("import sob demanda de org.junit.jupiter.api resolve @Test e @Nested")
        void importSobDemandaResolve() {
            assertThat(reactor.ler(FontesDeAuditoria.AG + "integracao/GraphiqlPorProfileIT.java")).contains("import org.junit.jupiter.api.*;");
            assertThat(reactor.auditar().suites()).extracting(Suite::nome).contains(GRAPHIQL);
        }
    }

    @Nested
    @DisplayName("Scenario: Desabilitação declarada no código de teste é recusada")
    class Desabilitacao {

        private static final String PARAM = FontesDeAuditoria.AG + "dominio/PeriodoParametrizadoTest.java";
        private static final String DOMINIO = FontesDeAuditoria.AG + "dominio/";

        private void anotarMetodo(String anotacaoEImport) {
            String[] partes = anotacaoEImport.split("\\|");
            reactor.substituir(PARAM, "import org.junit.jupiter.api.Test;", "import org.junit.jupiter.api.Test;\nimport " + partes[1] + ";");
            reactor.substituir(PARAM, "    @Test\n    void aceitaDuracaoValida", "    " + partes[0] + "\n    @Test\n    void aceitaDuracaoValida");
        }

        private void anotarMetodoSemImport(String anotacao) {
            reactor.substituir(PARAM, "    @Test\n    void aceitaDuracaoValida", "    " + anotacao + "\n    @Test\n    void aceitaDuracaoValida");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "@Disabled|org.junit.jupiter.api.Disabled",
                "@Ignore|org.junit.Ignore",
                "@EnabledOnOs(OS.LINUX)|org.junit.jupiter.api.condition.*",
                "@DisabledOnJre(JRE.JAVA_21)|org.junit.jupiter.api.condition.*",
                "@EnabledIfSystemProperty(named = \"ci\", matches = \"true\")|org.junit.jupiter.api.condition.EnabledIfSystemProperty",
                "@DisabledIfEnvironmentVariable(named = \"CI\", matches = \"true\")|org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable",
                "@EnabledForJreRange(min = JRE.JAVA_21)|org.junit.jupiter.api.condition.*",
                "@DisabledInNativeImage|org.junit.jupiter.api.condition.DisabledInNativeImage",
                "@EnabledIf(\"ligado\")|org.junit.jupiter.api.condition.EnabledIf",
                // Condicoes do Spring TestContext, reconhecidas pelo nome resolvido no import.
                "@EnabledIf(expression = \"${ci}\")|org.springframework.test.context.junit.jupiter.EnabledIf",
                "@DisabledIf(\"#{true}\")|org.springframework.test.context.junit.jupiter.DisabledIf"})
        @DisplayName("anotação de desabilitação ou condicional real, resolvida pelo import")
        void anotacaoEmMetodo(String anotacaoEImport) {
            anotarMetodo(anotacaoEImport);
            String anotacao = anotacaoEImport.substring(1, anotacaoEImport.indexOf('|'));
            String nome = anotacao.contains("(") ? anotacao.substring(0, anotacao.indexOf('(')) : anotacao;
            assertThat(violacoes()).containsExactly("desabilitacao declarada no codigo de teste: " + PARAM + ":14: " + nome
                    + " em " + PARAMETRIZADA + "#aceitaDuracaoValida");
        }

        @Test
        @DisplayName("condicional qualificada, sem import")
        void condicionalQualificada() {
            anotarMetodoSemImport("@org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)");
            assertThat(violacoes()).containsExactly("desabilitacao declarada no codigo de teste: " + PARAM
                    + ":13: org.junit.jupiter.api.condition.DisabledOnOs em " + PARAMETRIZADA + "#aceitaDuracaoValida");
        }

        @Test
        @DisplayName("@Disabled na classe")
        void desabilitadaNaClasse() {
            reactor.substituir(PARAM, "class PeriodoParametrizadoTest {", "@org.junit.jupiter.api.Disabled(\"depois\")\nclass PeriodoParametrizadoTest {");
            assertThat(violacoes()).containsExactly("desabilitacao declarada no codigo de teste: " + PARAM
                    + ":7: org.junit.jupiter.api.Disabled em " + PARAMETRIZADA);
        }

        @Test
        @DisplayName("@Testcontainers(disabledWithoutDocker = true) desabilita sem Docker e é recusada")
        void testcontainersSemDocker() {
            String adapter = FontesDeAuditoria.AG + "integracao/ConsultaRepositoryAdapterIT.java";
            reactor.substituir(adapter, "@Testcontainers\n", "@Testcontainers(disabledWithoutDocker = true)\n");
            assertThat(violacoes()).containsExactly("desabilitacao declarada no codigo de teste: " + adapter
                    + ":6: Testcontainers em " + ADAPTER);
        }

        @Test
        @DisplayName("meta-anotação local que resolve transitivamente para condição real é recusada no uso")
        void metaAnotacaoLocalCondicional() {
            reactor.escrever(DOMINIO + "SomenteNoLinux.java", """
                    package br.com.fiap.hospital.agendamento.dominio;

                    import java.lang.annotation.Retention;
                    import java.lang.annotation.RetentionPolicy;
                    import org.junit.jupiter.api.condition.EnabledOnOs;
                    import org.junit.jupiter.api.condition.OS;

                    @EnabledOnOs(OS.LINUX)
                    @Retention(RetentionPolicy.RUNTIME)
                    @interface SomenteNoLinux { }
                    """);
            reactor.escrever(DOMINIO + "SomenteEmCi.java", """
                    package br.com.fiap.hospital.agendamento.dominio;

                    @SomenteNoLinux
                    @interface SomenteEmCi { }
                    """);
            anotarMetodoSemImport("@SomenteEmCi");
            assertThat(violacoes()).containsExactly("desabilitacao declarada no codigo de teste: " + PARAM
                    + ":13: SomenteEmCi em " + PARAMETRIZADA + "#aceitaDuracaoValida");
        }

        @Test
        @DisplayName("anotação própria com prefixo EnabledIf, sem meta-anotação condicional, é aceita")
        void anotacaoPropriaComNomeParecidoAceita() {
            reactor.escrever(DOMINIO + "EnabledIfAuditoria.java", """
                    package br.com.fiap.hospital.agendamento.dominio;

                    import java.lang.annotation.Retention;
                    import java.lang.annotation.RetentionPolicy;

                    /** So documenta que o caso participa da auditoria: nao condiciona execucao. */
                    @Retention(RetentionPolicy.RUNTIME)
                    @interface EnabledIfAuditoria { }
                    """);
            reactor.escrever(DOMINIO + "DisabledOnOsDoDominio.java", """
                    package br.com.fiap.hospital.agendamento.dominio;

                    @interface DisabledOnOsDoDominio { String value() default ""; }
                    """);
            anotarMetodoSemImport("@EnabledIfAuditoria @DisabledOnOsDoDominio(\"linux\")");
            assertThat(violacoes()).isEmpty();
        }

        @Test
        @DisplayName("condição com nome de lista mas sem import que a identifique falha fechado")
        void condicionalNaoResolvida() {
            anotarMetodoSemImport("@EnabledOnOs(OS.LINUX)");
            assertThat(violacoes()).containsExactly(
                    "anotacao nao resolvida: " + PARAM + ":13: @EnabledOnOs (sem import que a identifique)");
        }

        @Test
        @DisplayName("o nome citado em texto ou comentário não é desabilitação")
        void citacaoNaoEDesabilitacao() {
            reactor.substituir(PARAM, "void aceitaDuracaoValida() { }",
                    "void aceitaDuracaoValida() { String s = \"@Disabled assumeTrue\"; /* @Ignore */ }");
            assertThat(violacoes()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Scenario: Desabilitação declarada no código de teste é recusada — suposição condicional")
    class Suposicao {

        private static final String PARAM = FontesDeAuditoria.AG + "dominio/PeriodoParametrizadoTest.java";

        private void corpo(String importacao, String corpo) {
            if (!importacao.isEmpty()) {
                reactor.substituir(PARAM, "import org.junit.jupiter.api.Test;", "import org.junit.jupiter.api.Test;\n" + importacao);
            }
            reactor.substituir(PARAM, "void aceitaDuracaoValida() { }", "void aceitaDuracaoValida() { " + corpo + " }");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "import static org.junit.jupiter.api.Assumptions.assumeTrue;|assumeTrue(true);|assumeTrue|org.junit.jupiter.api.Assumptions",
                "import static org.junit.jupiter.api.Assumptions.*;|assumingThat(true, () -> { });|assumingThat|org.junit.jupiter.api.Assumptions",
                "import org.junit.jupiter.api.Assumptions;|Assumptions.assumeFalse(false);|Assumptions.assumeFalse|org.junit.jupiter.api.Assumptions",
                "import org.junit.jupiter.api.*;|Assumptions.abort(\"sem ambiente\");|Assumptions.abort|org.junit.jupiter.api.Assumptions",
                "|org.junit.jupiter.api.Assumptions.assumeTrue(true);|org.junit.jupiter.api.Assumptions.assumeTrue|org.junit.jupiter.api.Assumptions",
                "import org.junit.Assume;|Assume.assumeNotNull(this);|Assume.assumeNotNull|org.junit.Assume",
                "import static org.assertj.core.api.Assumptions.assumeThat;|assumeThat(1).isEqualTo(1);|assumeThat|org.assertj.core.api.Assumptions",
                "import static org.assertj.core.api.BDDAssumptions.*;|given(1).isPositive();|given|org.assertj.core.api.BDDAssumptions",
                "import org.junit.jupiter.api.Assumptions;|java.util.function.Consumer<Boolean> c = Assumptions::assumeTrue;|Assumptions::assumeTrue|org.junit.jupiter.api.Assumptions"})
        @DisplayName("chamada real às APIs de suposição, resolvida pelo import ou pelo nome qualificado")
        void chamadaReal(String caso) {
            String[] partes = caso.split("\\|");
            corpo(partes[0], partes[1]);
            String linha = partes[0].isEmpty() ? "14" : "15";
            assertThat(violacoes()).containsExactly("suposicao condicional no codigo de teste: " + PARAM + ":" + linha + ": "
                    + partes[2] + " (" + partes[3] + ")");
        }

        @Test
        @DisplayName("método local assumeFormatoValido é aceito, mesmo com import estático sob demanda da API")
        void metodoLocalComPrefixoAceito() {
            corpo("import static org.junit.jupiter.api.Assumptions.*;", "assumeFormatoValido();");
            reactor.substituir(PARAM, "    @Test\n    void aceitaDuracaoValida", "    private void assumeFormatoValido() { }\n\n    @Test\n    void aceitaDuracaoValida");
            assertThat(violacoes()).isEmpty();
        }

        @Test
        @DisplayName("método local de mesmo nome sombreia o import estático da API")
        void metodoLocalSombreiaImportEstatico() {
            corpo("import static org.junit.jupiter.api.Assumptions.*;", "assumeTrue(true);");
            reactor.substituir(PARAM, "    @Test\n    void aceitaDuracaoValida", "    static void assumeTrue(boolean condicao) { }\n\n    @Test\n    void aceitaDuracaoValida");
            assertThat(violacoes()).isEmpty();
        }

        @Test
        @DisplayName("Assumptions.setPreferredAssumptionException do AssertJ só configura e é aceito, qualificado e por import estático")
        void setPreferredAssumptionExceptionAceito() {
            corpo("import org.assertj.core.api.Assumptions;\nimport static org.assertj.core.api.Assumptions.setPreferredAssumptionException;",
                    "Assumptions.setPreferredAssumptionException(org.assertj.core.configuration.PreferredAssumptionException.JUNIT5);"
                            + " setPreferredAssumptionException(org.assertj.core.configuration.PreferredAssumptionException.AUTO_DETECT);");
            assertThat(violacoes()).isEmpty();
        }

        @Test
        @DisplayName("com setPreferredAssumptionException aceito, assumeThat real na mesma classe continua recusado")
        void setPreferredAceitoEAssumeThatRecusado() {
            corpo("import org.assertj.core.api.Assumptions;",
                    "Assumptions.setPreferredAssumptionException(org.assertj.core.configuration.PreferredAssumptionException.JUNIT5);"
                            + " Assumptions.assumeThat(1).isPositive();");
            assertThat(violacoes()).containsExactly("suposicao condicional no codigo de teste: " + PARAM
                    + ":15: Assumptions.assumeThat (org.assertj.core.api.Assumptions)");
        }

        @Test
        @DisplayName("tipo local homônimo de Assumptions não é a API")
        void tipoLocalHomonimo() {
            reactor.escrever(FontesDeAuditoria.AG + "dominio/Assumptions.java", """
                    package br.com.fiap.hospital.agendamento.dominio;

                    final class Assumptions {
                        static void assumeTrue(boolean condicao) { }
                    }
                    """);
            corpo("", "Assumptions.assumeTrue(true);");
            assertThat(violacoes()).isEmpty();
        }

        @Test
        @DisplayName("import da API sem chamada e chamada homônima de outra API são aceitos")
        void importSemChamadaEOutraApi() {
            corpo("import org.junit.jupiter.api.Assumptions;\nimport static org.mockito.BDDMockito.given;", "given(null);");
            assertThat(violacoes()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Scenario: Relatório de outra sessão é recusado pela auditoria")
    class Sessao {

        @Test
        @DisplayName("marcador ausente")
        void marcadorAusente() {
            reactor.apagar(raiz.resolve(SessaoDeVerificacao.MARCADOR));
            assertThat(violacoes()).containsExactly("relatorios obsoletos, fora da sessao corrente: marcador de sessao ausente: "
                    + raiz.resolve(SessaoDeVerificacao.MARCADOR));
        }

        @Test
        @DisplayName("marcador divergente, com todos os relatórios presentes")
        void marcadorDivergente() {
            reactor.escrever(SessaoDeVerificacao.MARCADOR.toString(), "2026-09-13T09:59:59.999Z");
            assertThat(violacoes()).containsExactly("relatorios obsoletos, fora da sessao corrente: marcador de outra sessao: "
                    + "esperado [" + ReactorDeAuditoria.SESSAO + "], gravado [2026-09-13T09:59:59.999Z]");
        }

        @Test
        @DisplayName("identificador não resolvido pelo Maven")
        void identificadorNaoResolvido() {
            assertThat(AuditoriaDeExecucao.auditar(raiz, "${sessao.verificacao}", reactor.propriedades).violacoes()).containsExactly(
                    "relatorios obsoletos, fora da sessao corrente: identificador de sessao nao informado: ${sessao.verificacao}");
        }
    }

    @Nested
    @DisplayName("Scenario: Filtro de seleção, omissão ou tolerância é recusado — por parâmetro")
    class FiltroPorParametro {

        static Stream<String> propriedades() {
            return PropriedadesDeFiltro.todas().stream();
        }

        @ParameterizedTest(name = "-D{0}")
        @MethodSource("propriedades")
        @DisplayName("cada propriedade da tabela, uma por vez, com todos os relatórios presentes")
        void cadaPropriedade(String propriedade) {
            String valor = propriedade.equals("dependenciesToScan") ? "br.com.fiap.hospital:shared-contracts" : "valor";
            reactor.propriedades.put(propriedade, valor);
            assertThat(violacoes()).containsExactly("filtro ativo por parametro (" + PropriedadesDeFiltro.classeDe(propriedade)
                    + "): -D" + propriedade + "=" + valor + ": o gate global nao aceita filtro");
        }

        @Test
        @DisplayName("a tabela tem todas as classes do D5 e a conferência da 3.2.5")
        void tabelaCompleta() {
            assertThat(PropriedadesDeFiltro.TABELA.keySet()).containsExactly("selecao por nome", "exclusao por nome", "tags",
                    "motores", "suites declaradas", "ampliacao por dependencia", "omissao", "tolerancia",
                    "tolerancia a selecao vazia");
            assertThat(PropriedadesDeFiltro.todas()).hasSize(31).doesNotHaveDuplicates()
                    .contains("dependenciesToScan", "it.test", "maven.test.skip.exec", "it.failIfNoSpecifiedTests")
                    .doesNotContain("surefire.dependenciesToScan", "failsafe.dependenciesToScan");
        }

        @Test
        @DisplayName("valor padrão explícito também é filtro declarado")
        void valorPadraoExplicito() {
            reactor.propriedades.put("skipTests", "false");
            assertThat(violacoes()).containsExactly(
                    "filtro ativo por parametro (omissao): -DskipTests=false: o gate global nao aceita filtro");
        }

        @Test
        @DisplayName("propriedade não repassada à auditoria é recusada")
        void propriedadeNaoRepassada() {
            reactor.propriedades.remove("groups");
            assertThat(violacoes()).containsExactly("propriedade groups nao repassada a auditoria: a recusa nao pode ser conferida");
        }
    }

    @Nested
    @DisplayName("Scenario: Filtro de seleção, omissão ou tolerância é recusado — por configuração de build")
    class FiltroPorConfiguracao {

        private static final String SUREFIRE_DO_MODULO = """
                <project><artifactId>agendamento</artifactId><build><plugins><plugin>
                <groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId>
                <configuration>%s</configuration></plugin></plugins></build></project>""";

        @ParameterizedTest(name = "<{0}>")
        @FieldSource("br.com.fiap.hospital.qualidade.ConfiguracaoDosPlugins#ELEMENTOS_PROIBIDOS")
        @DisplayName("cada elemento proibido, um por vez, no POM de um único módulo")
        void elementoProibidoNoModulo(String elemento) {
            reactor.escrever("agendamento/pom.xml", SUREFIRE_DO_MODULO.formatted("<" + elemento + ">x</" + elemento + ">"));
            assertThat(violacoes()).containsExactly(
                    "agendamento: maven-surefire-plugin declara <" + elemento + ">: o gate global nao aceita filtro");
        }

        @Test
        @DisplayName("<excludes> acrescentado")
        void excludesAcrescentado() {
            reactor.escrever("agendamento/pom.xml", SUREFIRE_DO_MODULO.formatted("<excludes><exclude>**/Periodo*</exclude></excludes>"));
            assertThat(violacoes()).containsExactly("agendamento: maven-surefire-plugin declara <excludes>: o gate global nao aceita filtro");
        }

        @Test
        @DisplayName("<dependenciesToScan> acrescentado no plugin central da raiz")
        void dependenciesToScanNaRaiz() {
            reactor.substituir("pom.xml", "<argLine>@{argLine}</argLine>",
                    "<argLine>@{argLine}</argLine><dependenciesToScan><dependency>br.com.fiap.hospital:shared-contracts</dependency></dependenciesToScan>");
            assertThat(violacoes()).containsExactly("raiz: maven-surefire-plugin declara <dependenciesToScan>: o gate global nao aceita filtro");
        }

        @Test
        @DisplayName("include redefinido num módulo")
        void includeRedefinidoNoModulo() {
            reactor.escrever("agendamento/pom.xml", SUREFIRE_DO_MODULO.formatted("<includes><include>**/*Test.java</include></includes>"));
            assertThat(violacoes()).containsExactly("agendamento: maven-surefire-plugin redefine includes: [**/*Test.java]");
        }

        @Test
        @DisplayName("mudança do include normativo é recusada e acompanhada pela classificação")
        void includeAlteradoNaRaiz() {
            reactor.substituir("pom.xml", "<include>**/*IT.java</include>", "<include>**/*ITCase.java</include>");
            List<String> violacoes = violacoes();
            assertThat(violacoes).startsWith(
                    "raiz: includes do maven-failsafe-plugin devem ser exatamente **/*IT.java, e sao [**/*ITCase.java]");
            assertThat(violacoes).anyMatch(v -> v.startsWith("teste fora da convencao: agendamento: " + ADAPTER.replace('$', '.'))
                    && v.contains("nao termina em Test nem ITCase"));
        }

        @Test
        @DisplayName("filtro declarado só num perfil de um módulo")
        void filtroSoNumPerfil() {
            reactor.escrever("seguranca/pom.xml", """
                    <project><artifactId>seguranca</artifactId><profiles><profile><id>rapido</id><build><plugins><plugin>
                    <groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId>
                    <configuration><excludedGroups>lento</excludedGroups></configuration>
                    </plugin></plugins></build></profile></profiles></project>""");
            assertThat(violacoes()).containsExactly(
                    "seguranca (perfil rapido): maven-surefire-plugin declara <excludedGroups>: o gate global nao aceita filtro");
        }

        @Test
        @DisplayName("filtro numa execução do plugin")
        void filtroNaExecucao() {
            reactor.substituir("pom.xml", "<id>integration-tests</id>", "<id>integration-tests</id><configuration><testFailureIgnore>true</testFailureIgnore></configuration>");
            assertThat(violacoes()).containsExactly(
                    "raiz (execucao integration-tests): maven-failsafe-plugin declara <testFailureIgnore>: o gate global nao aceita filtro");
        }

        @Test
        @DisplayName("filtro em pluginManagement")
        void filtroEmPluginManagement() {
            reactor.escrever("seguranca/pom.xml", """
                    <project><artifactId>seguranca</artifactId><build><pluginManagement><plugins><plugin>
                    <groupId>org.apache.maven.plugins</groupId><artifactId>maven-failsafe-plugin</artifactId>
                    <configuration><skipITs>true</skipITs></configuration>
                    </plugin></plugins></pluginManagement></build></project>""");
            assertThat(violacoes()).containsExactly(
                    "seguranca (pluginManagement): maven-failsafe-plugin declara <skipITs>: o gate global nao aceita filtro");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"skipTests", "maven.test.failure.ignore", "groups", "dependenciesToScan"})
        @DisplayName("propriedade de filtro em <properties> de um módulo")
        void propriedadeNoModulo(String propriedade) {
            reactor.escrever("agendamento/pom.xml", "<project><artifactId>agendamento</artifactId><properties><" + propriedade + ">x</"
                    + propriedade + "></properties></project>");
            assertThat(violacoes()).containsExactly("agendamento: <properties> define " + propriedade + " ("
                    + PropriedadesDeFiltro.classeDe(propriedade) + "): o gate global nao aceita filtro");
        }

        @Test
        @DisplayName("propriedade de filtro em <properties> de um perfil")
        void propriedadeNoPerfil() {
            reactor.escrever("agendamento/pom.xml", "<project><artifactId>agendamento</artifactId><profiles><profile><id>ci</id>"
                    + "<properties><skipITs>true</skipITs></properties></profile></profiles></project>");
            assertThat(violacoes()).containsExactly("agendamento (perfil ci): <properties> define skipITs (omissao): o gate global nao aceita filtro");
        }
    }

    @Nested
    @DisplayName("Scenario: Filtro de seleção, omissão ou tolerância é recusado — chaves internas do provider em <properties>")
    class ChavesDoProvider {

        private static final String SUREFIRE_DO_MODULO = """
                <project><artifactId>agendamento</artifactId><build><plugins><plugin>
                <groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId>
                <configuration><properties>%s</properties></configuration></plugin></plugins></build></project>""";

        private static final String FAILSAFE_DO_MODULO = """
                <project><artifactId>agendamento</artifactId><build><plugins><plugin>
                <groupId>org.apache.maven.plugins</groupId><artifactId>maven-failsafe-plugin</artifactId>
                <configuration><properties>%s</properties></configuration></plugin></plugins></build></project>""";

        private static String mensagem(String local, String chave, String valor) {
            return local + " declara <properties> " + chave + "=" + valor + ": chave lida pelo provider JUnit Platform do"
                    + " Surefire 3.2.5 para selecionar " + (chave.endsWith("groups") ? "tags" : "motores")
                    + ": o gate global nao aceita filtro";
        }

        private static String comoElemento(String chave, String valor) {
            return "<" + chave + ">" + valor + "</" + chave + ">";
        }

        private static String comoProperty(String chave, String valor) {
            return "<property><name>" + chave + "</name><value>" + valor + "</value></property>";
        }

        @ParameterizedTest(name = "<{0}>")
        @FieldSource("br.com.fiap.hospital.qualidade.ConfiguracaoDosPlugins#CHAVES_DO_PROVIDER")
        @DisplayName("cada chave, isolada, como elemento em <properties> do Surefire de um módulo, com todos os relatórios presentes")
        void cadaChaveComoElemento(String chave) {
            reactor.escrever("agendamento/pom.xml", SUREFIRE_DO_MODULO.formatted(comoElemento(chave, "lento")));
            assertThat(violacoes()).containsExactly(mensagem("agendamento: maven-surefire-plugin", chave, "lento"));
        }

        @ParameterizedTest(name = "<property> {0}")
        @FieldSource("br.com.fiap.hospital.qualidade.ConfiguracaoDosPlugins#CHAVES_DO_PROVIDER")
        @DisplayName("cada chave, isolada, na representação <property><name>/<value> do Failsafe de um módulo")
        void cadaChaveComoProperty(String chave) {
            reactor.escrever("agendamento/pom.xml", FAILSAFE_DO_MODULO.formatted(comoProperty(chave, "junit-vintage")));
            assertThat(violacoes()).containsExactly(mensagem("agendamento: maven-failsafe-plugin", chave, "junit-vintage"));
        }

        @Test
        @DisplayName("no plugin central da raiz")
        void noPluginDaRaiz() {
            reactor.substituir("pom.xml", "<argLine>@{argLine}</argLine>",
                    "<argLine>@{argLine}</argLine><properties>" + comoElemento("groups", "rapido") + "</properties>");
            assertThat(violacoes()).containsExactly(mensagem("raiz: maven-surefire-plugin", "groups", "rapido"));
        }

        @Test
        @DisplayName("numa execução do plugin")
        void numaExecucao() {
            reactor.substituir("pom.xml", "<id>integration-tests</id>", "<id>integration-tests</id><configuration><properties>"
                    + comoProperty("excludegroups", "lento") + "</properties></configuration>");
            assertThat(violacoes()).containsExactly(mensagem("raiz (execucao integration-tests): maven-failsafe-plugin", "excludegroups", "lento"));
        }

        @Test
        @DisplayName("em pluginManagement de um módulo")
        void emPluginManagement() {
            reactor.escrever("seguranca/pom.xml", """
                    <project><artifactId>seguranca</artifactId><build><pluginManagement><plugins><plugin>
                    <groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId>
                    <configuration><properties><includejunit5engines>junit-vintage</includejunit5engines></properties></configuration>
                    </plugin></plugins></pluginManagement></build></project>""");
            assertThat(violacoes()).containsExactly(
                    mensagem("seguranca (pluginManagement): maven-surefire-plugin", "includejunit5engines", "junit-vintage"));
        }

        @Test
        @DisplayName("num perfil de um módulo")
        void numPerfil() {
            reactor.escrever("seguranca/pom.xml", """
                    <project><artifactId>seguranca</artifactId><profiles><profile><id>ci</id><build><plugins><plugin>
                    <groupId>org.apache.maven.plugins</groupId><artifactId>maven-failsafe-plugin</artifactId>
                    <configuration><properties><property><name>excludejunit5engines</name><value>junit-jupiter</value></property></properties></configuration>
                    </plugin></plugins></build></profile></profiles></project>""");
            assertThat(violacoes()).containsExactly(
                    mensagem("seguranca (perfil ci): maven-failsafe-plugin", "excludejunit5engines", "junit-jupiter"));
        }

        @Test
        @DisplayName("chaves legítimas ou só parecidas em <properties>, nas duas representações, são aceitas: igualdade exata, não substring")
        void chavesParecidasAceitas() {
            reactor.escrever("agendamento/pom.xml", SUREFIRE_DO_MODULO.formatted(
                    comoProperty("usedefaultlisteners", "false") + comoElemento("groupsDoProjeto", "x")
                            + comoProperty("excludedGroupsLegado", "y") + comoElemento("junit5engines", "z")));
            assertThat(violacoes()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Scenario: Filtro de seleção, omissão ou tolerância é recusado — alias customizado sobrescrevível pela linha de comando")
    class Interpolacao {

        private static final String DRY_RUN = "junit.platform.execution.dryRun.enabled";
        private static final String LISTENER = "junit.platform.discovery.listener.default";
        private static final String OMITE = "parametro do JUnit Platform que nao executa nenhum teste (dry-run): " + DRY_RUN
                + "=true: o gate global nao aceita omissao nem tolerancia";
        private static final String SUREFIRE = "agendamento: maven-surefire-plugin ";
        private static final String OCULTA = " em posicao capaz de ocultar configuracao de teste: uma user property"
                + " da linha de comando o sobrescreve e a auditoria nao ve o valor recebido";
        private static final String AGENTE = "@{argLine} -javaagent:${project.build.directory}/agentes/mockito-core.jar";

        /** POM do modulo com propriedades e configuracao do Surefire; todos os relatorios continuam presentes. */
        private void modulo(String propriedades, String configuracao) {
            reactor.escrever("agendamento/pom.xml", "<project><artifactId>agendamento</artifactId><properties>" + propriedades
                    + "</properties><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>"
                    + "<artifactId>maven-surefire-plugin</artifactId><configuration>" + configuracao
                    + "</configuration></plugin></plugins></build></project>");
        }

        private static String parametros(String conteudo) {
            return "<properties><configurationParameters>" + conteudo + "</configurationParameters></properties>";
        }

        private static String alias(String canal, String descricao) {
            return SUREFIRE + canal + ": alias " + descricao + OCULTA;
        }

        @Test
        @DisplayName("bypass: alias com valor false no POM em configurationParameters é recusado — a linha de comando o sobrescreve")
        void aliasFalsoNoPomRecusado() {
            modulo("<m10.dry-run>false</m10.dry-run>", parametros(DRY_RUN + "=${m10.dry-run}"));
            assertThat(violacoes()).containsExactly(alias("<configurationParameters>", "${m10.dry-run} (no POM: false)"));
        }

        @Test
        @DisplayName("simulação de user property: -Dm10.dry-run=true sobrescreve o false do POM sem chegar à auditoria, e o alias é recusado")
        void userPropertySobrescreveValorSeguro() {
            modulo("<m10.dry-run>false</m10.dry-run>", parametros(DRY_RUN + "=${m10.dry-run}"));
            // mvn verify -Dm10.dry-run=true: o JUnit recebe true, mas a auditoria so recebe os filtros e os canais conhecidos
            assertThat(reactor.propriedades).doesNotContainKey("m10.dry-run");
            assertThat(violacoes()).containsExactly(alias("<configurationParameters>", "${m10.dry-run} (no POM: false)"));
        }

        @Test
        @DisplayName("alias com valor true no POM é recusado como alias e pelo valor já escrito")
        void aliasVerdadeiroNoPom() {
            modulo("<m10.dry-run>true</m10.dry-run>", parametros(DRY_RUN + "=${m10.dry-run}"));
            assertThat(violacoes()).containsExactly(alias("<configurationParameters>", "${m10.dry-run} (no POM: true)"),
                    SUREFIRE + "<configurationParameters>: " + OMITE);
        }

        @Test
        @DisplayName("conteúdo inteiro por alias é recusado, mesmo com parâmetros legítimos no POM")
        void conteudoInteiroPorAlias() {
            modulo("<m10.junit.parameters>junit.jupiter.execution.parallel.enabled=true</m10.junit.parameters>",
                    parametros("${m10.junit.parameters}"));
            assertThat(violacoes()).containsExactly(alias("<configurationParameters>",
                    "${m10.junit.parameters} (no POM: junit.jupiter.execution.parallel.enabled=true)"));
        }

        @Test
        @DisplayName("systemPropertyVariables com alias no valor do dry-run é recusado")
        void systemPropertyVariablesPorAlias() {
            modulo("<m10.dry-run>false</m10.dry-run>", "<systemPropertyVariables><" + DRY_RUN + ">${m10.dry-run}</" + DRY_RUN
                    + "></systemPropertyVariables>");
            assertThat(violacoes()).containsExactly(alias("<systemPropertyVariables>", "${m10.dry-run} (no POM: false)"));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"elemento", "property"})
        @DisplayName("systemProperties nas duas representações com alias no valor do dry-run é recusado")
        void systemPropertiesPorAlias(String representacao) {
            String declaracao = representacao.equals("elemento")
                    ? "<" + DRY_RUN + ">${m10.dry-run}</" + DRY_RUN + ">"
                    : "<property><name>" + DRY_RUN + "</name><value>${m10.dry-run}</value></property>";
            modulo("<m10.dry-run>false</m10.dry-run>", "<systemProperties>" + declaracao + "</systemProperties>");
            assertThat(violacoes()).containsExactly(alias("<systemProperties>", "${m10.dry-run} (no POM: false)"));
        }

        @Test
        @DisplayName("listener de descoberta escondido por alias é recusado, mesmo com abortOnFailure no POM")
        void listenerPorAlias() {
            modulo("<m10.listener>abortOnFailure</m10.listener>", "<systemPropertyVariables><" + LISTENER + ">${m10.listener}</"
                    + LISTENER + "></systemPropertyVariables>");
            assertThat(violacoes()).containsExactly(alias("<systemPropertyVariables>", "${m10.listener} (no POM: abortOnFailure)"));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"${m10.test.args}", "@{m10.test.args}"})
        @DisplayName("argLine com ${m10.test.args} e com @{m10.test.args} é recusado, mesmo com valor seguro no POM")
        void argLinePorAlias(String expressao) {
            modulo("<m10.test.args>-Dfoo=bar</m10.test.args>", "<argLine>@{argLine} " + expressao + "</argLine>");
            assertThat(violacoes()).containsExactly(alias("<argLine>", expressao + " (no POM: -Dfoo=bar)"));
        }

        @Test
        @DisplayName("argLine de <properties> do projeto com alias é recusado")
        void argLineDoProjetoPorAlias() {
            modulo("<argLine>@{m10.test.args}</argLine>", "");
            assertThat(violacoes()).containsExactly("agendamento: <properties> argLine: alias @{m10.test.args} (sem valor no POM)" + OCULTA);
        }

        @Test
        @DisplayName("sobrescrita hostil da coordenada do Mockito: ${org.mockito:mockito-core:jar} no argLine é recusada")
        void coordenadaDoMockitoRecusada() {
            reactor.substituir("pom.xml", "<argLine>@{argLine}</argLine>",
                    "<argLine>@{argLine} -javaagent:${org.mockito:mockito-core:jar}</argLine>");
            // -Dorg.mockito:mockito-core:jar="caminho -Djunit.platform.execution.dryRun.enabled=true" nao chega a auditoria
            assertThat(reactor.propriedades).doesNotContainKey("org.mockito:mockito-core:jar");
            assertThat(violacoes()).containsExactly("raiz: maven-surefire-plugin <argLine>: alias ${org.mockito:mockito-core:jar}"
                    + " (sem valor no POM)" + OCULTA);
        }

        @Test
        @DisplayName("caminho de projeto que introduz -D pelo ${project.build.directory} é recusado")
        void caminhoDeProjetoComD() {
            Path projeto = Path.of("repo -D" + DRY_RUN + "=true x");
            List<String> violacoes = ParametrosDoJUnitPlatform.verificarArgLine("canal", AGENTE, java.util.Map.of(),
                    ConfiguracaoDosPlugins.Escopo.doProjeto(projeto).proprias());
            assertThat(violacoes).containsExactly("canal: " + OMITE);
        }

        @Test
        @DisplayName("<build><directory> redefinido torna ${project.build.directory} um alias")
        void diretorioDeBuildRedefinido() {
            reactor.escrever("agendamento/pom.xml", "<project><artifactId>agendamento</artifactId><properties><m10.saida>saida"
                    + "</m10.saida></properties><build><directory>${m10.saida}</directory><plugins><plugin>"
                    + "<groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><configuration>"
                    + "<argLine>" + AGENTE + "</argLine></configuration></plugin></plugins></build></project>");
            assertThat(violacoes()).containsExactly(alias("<argLine>", "${project.build.directory} (sem valor no POM)"));
        }

        @Test
        @DisplayName("alias no caminho de systemPropertiesFile é recusado, mesmo apontando um arquivo legítimo")
        void systemPropertiesFilePorAlias() {
            reactor.escrever("agendamento/sistema.properties", "junit.jupiter.execution.parallel.enabled=true\n");
            modulo("<m10.arquivo>sistema.properties</m10.arquivo>", "<systemPropertiesFile>${m10.arquivo}</systemPropertiesFile>");
            assertThat(violacoes()).containsExactly(alias("<systemPropertiesFile>", "${m10.arquivo} (no POM: sistema.properties)"));
        }

        @Test
        @DisplayName("nome de <property> por alias é recusado")
        void nomePorAlias() {
            modulo("<m10.chave>groups</m10.chave>", "<properties><property><name>${m10.chave}</name><value>lento</value></property></properties>");
            assertThat(violacoes()).containsExactly(alias("<properties> (nome de propriedade)", "${m10.chave} (no POM: groups)"));
        }

        @Test
        @DisplayName("propriedade da raiz e do perfil servem só ao diagnóstico: o alias é recusado nos dois escopos")
        void propriedadesDaRaizEDoPerfil() {
            reactor.substituir("pom.xml", "<modules>", "<properties><m10.dry-run>false</m10.dry-run></properties><modules>");
            reactor.escrever("agendamento/pom.xml", "<project><artifactId>agendamento</artifactId>"
                    + "<build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId>"
                    + "<configuration>" + parametros(DRY_RUN + "=${m10.dry-run}") + "</configuration></plugin></plugins></build>"
                    + "<profiles><profile><id>ci</id><properties><m10.dry-run>true</m10.dry-run></properties>"
                    + "<build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId>"
                    + "<configuration>" + parametros(DRY_RUN + "=${m10.dry-run}") + "</configuration></plugin></plugins></build>"
                    + "</profile></profiles></project>");
            assertThat(violacoes()).containsExactly(alias("<configurationParameters>", "${m10.dry-run} (no POM: false)"),
                    "agendamento (perfil ci): maven-surefire-plugin <configurationParameters>: alias ${m10.dry-run} (no POM: true)" + OCULTA,
                    "agendamento (perfil ci): maven-surefire-plugin <configurationParameters>: " + OMITE);
        }

        @Test
        @DisplayName("ciclo de interpolação no argLine é recusado como alias, sem exceção")
        void cicloDeInterpolacao() {
            modulo("<m10.a>${m10.b}</m10.a><m10.b>${m10.a}</m10.b>", "<argLine>@{argLine} ${m10.a}</argLine>");
            assertThat(violacoes()).containsExactly(alias("<argLine>", "${m10.a} (no POM: ${m10.b})"));
        }

        @Test
        @DisplayName("-DargLine=-Djunit.platform.execution.dryRun.enabled=true reprova pelo valor efetivo repassado")
        void argLineHostilPelaLinhaDeComando() {
            reactor.propriedades.put("argLine", "-D" + DRY_RUN + "=true");
            assertThat(violacoes()).containsExactly("linha de comando (argLine): " + OMITE);
        }

        @Test
        @DisplayName("dryRun=false literal em configurationParameters e systemPropertyVariables é aceito")
        void dryRunFalsoLiteral() {
            modulo("", parametros(DRY_RUN + "=false") + "<systemPropertyVariables><" + DRY_RUN + ">false</" + DRY_RUN
                    + "></systemPropertyVariables>");
            assertThat(violacoes()).isEmpty();
        }

        @Test
        @DisplayName("configurationParameters literais legítimos são aceitos")
        void configurationParametersLiteraisLegitimos() {
            modulo("", parametros("junit.jupiter.execution.parallel.enabled=true\n" + LISTENER + "=abortOnFailure\n"
                    + "junit.platform.execution.listeners.deactivate=algum.Listener"));
            assertThat(violacoes()).isEmpty();
        }

        @Test
        @DisplayName("argLine normativo, com o agente do Mockito em caminho determinístico, é aceito")
        void argLineNormativo() {
            reactor.substituir("pom.xml", "<argLine>@{argLine}</argLine>", "<argLine>" + AGENTE + "</argLine>");
            assertThat(violacoes()).isEmpty();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"@{argLine}", "${argLine}"})
        @DisplayName("argLine do POM com o valor efetivo seguro pela linha de comando é aceito")
        void argLineComValorEfetivoSeguro(String expressao) {
            reactor.substituir("pom.xml", "<argLine>@{argLine}</argLine>", "<argLine>" + expressao + "</argLine>");
            reactor.propriedades.put("argLine", "-javaagent:/m2/jacocoagent.jar=destfile=/alvo/jacoco.exec");
            assertThat(violacoes()).isEmpty();
        }

        @Test
        @DisplayName("propriedade comum fora dos canais de teste é aceita")
        void propriedadeComumForaDosCanais() {
            modulo("<m10.dry-run>true</m10.dry-run><app.titulo>${m10.inexistente}</app.titulo>",
                    "<systemPropertyVariables><app.nome>${m10.inexistente}</app.nome></systemPropertyVariables>"
                            + "<argLine>@{argLine}</argLine>");
            assertThat(violacoes()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Scenario: Filtro de seleção, omissão ou tolerância é recusado — parâmetros do JUnit Platform")
    class ParametrosJUnitPlatform {

        private static final String DRY_RUN = "junit.platform.execution.dryRun.enabled";
        private static final String OMITE = "parametro do JUnit Platform que nao executa nenhum teste (dry-run): ";
        private static final String TOLERA = "parametro do JUnit Platform que tolera falha de descoberta: ";
        private static final String FIM = ": o gate global nao aceita omissao nem tolerancia";

        /** Configuracao legitima: nenhuma dessas chaves seleciona, omite ou tolera testes. */
        private static final String LEGITIMOS = """
                junit.jupiter.execution.parallel.enabled=true
                junit.jupiter.execution.parallel.mode.default=concurrent
                junit.jupiter.testinstance.lifecycle.default=per_class
                junit.jupiter.extensions.autodetection.enabled=true
                junit.platform.execution.listeners.deactivate=algum.Listener
                junit.jupiter.conditions.deactivate=*
                junit.platform.execution.dryRun.enabled=false
                junit.platform.discovery.listener.default=abortOnFailure
                """;

        private static final String SUREFIRE_DO_MODULO = """
                <project><artifactId>agendamento</artifactId><build><plugins><plugin>
                <groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId>
                <configuration>%s</configuration></plugin></plugins></build></project>""";

        private static String comoElementos(String propriedades) {
            StringBuilder xml = new StringBuilder();
            ParametrosDoJUnitPlatform.propriedades(propriedades).forEach((k, v) -> xml.append('<').append(k).append('>').append(v)
                    .append("</").append(k).append('>'));
            return xml.toString();
        }

        private static String comoPropertyDeSistema(String propriedades) {
            StringBuilder xml = new StringBuilder();
            ParametrosDoJUnitPlatform.propriedades(propriedades).forEach((k, v) -> xml.append("<property><name>").append(k)
                    .append("</name><value>").append(v).append("</value></property>"));
            return xml.toString();
        }

        private static String comoArgLine(String propriedades) {
            StringBuilder argLine = new StringBuilder("@{argLine} -javaagent:agente.jar=destfile=jacoco.exec");
            ParametrosDoJUnitPlatform.propriedades(propriedades).forEach((k, v) -> argLine.append(" -D").append(k).append('=').append(v));
            return argLine.toString();
        }

        /** Declara o conteudo no canal dado e devolve o prefixo esperado da mensagem. */
        private String declarar(String canal, String propriedades) {
            switch (canal) {
                case "configurationParameters" -> reactor.escrever("agendamento/pom.xml", SUREFIRE_DO_MODULO.formatted(
                        "<properties><configurationParameters>" + propriedades + "</configurationParameters></properties>"));
                case "systemPropertyVariables" -> reactor.escrever("agendamento/pom.xml", SUREFIRE_DO_MODULO.formatted(
                        "<systemPropertyVariables>" + comoElementos(propriedades) + "</systemPropertyVariables>"));
                case "systemProperties" -> reactor.escrever("agendamento/pom.xml", SUREFIRE_DO_MODULO.formatted(
                        "<systemProperties>" + comoPropertyDeSistema(propriedades) + "</systemProperties>"));
                case "systemProperties como elemento" -> {
                    reactor.escrever("agendamento/pom.xml", SUREFIRE_DO_MODULO.formatted(
                            "<systemProperties>" + comoElementos(propriedades) + "</systemProperties>"));
                    return "agendamento: maven-surefire-plugin <systemProperties>: ";
                }
                case "configurationParameters como property" -> {
                    reactor.escrever("agendamento/pom.xml", SUREFIRE_DO_MODULO.formatted("<properties><property>"
                            + "<name>configurationParameters</name><value>" + propriedades + "</value></property></properties>"));
                    return "agendamento: maven-surefire-plugin <configurationParameters>: ";
                }
                case "argLine" -> reactor.escrever("agendamento/pom.xml", SUREFIRE_DO_MODULO.formatted(
                        "<argLine>" + comoArgLine(propriedades) + "</argLine>"));
                case "systemPropertiesFile" -> {
                    reactor.escrever("agendamento/sistema.properties", propriedades);
                    reactor.escrever("agendamento/pom.xml", SUREFIRE_DO_MODULO.formatted(
                            "<systemPropertiesFile>${project.basedir}/sistema.properties</systemPropertiesFile>"));
                    return "agendamento: maven-surefire-plugin <systemPropertiesFile> (sistema.properties): ";
                }
                case "junit-platform.properties de teste" -> {
                    reactor.escrever("agendamento/src/test/resources/junit-platform.properties", propriedades);
                    return "agendamento/src/test/resources/junit-platform.properties (junit-platform.properties): ";
                }
                case "junit-platform.properties principal" -> {
                    reactor.escrever("agendamento/src/main/resources/junit-platform.properties", propriedades);
                    return "agendamento/src/main/resources/junit-platform.properties (junit-platform.properties): ";
                }
                default -> throw new IllegalArgumentException(canal);
            }
            return "agendamento: maven-surefire-plugin <" + canal + ">: ";
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"configurationParameters", "configurationParameters como property", "systemPropertyVariables",
                "systemProperties", "systemProperties como elemento", "argLine", "systemPropertiesFile",
                "junit-platform.properties de teste", "junit-platform.properties principal"})
        @DisplayName("configuração legítima do JUnit Platform é aceita em cada canal")
        void legitimosAceitos(String canal) {
            declarar(canal, LEGITIMOS);
            assertThat(violacoes()).isEmpty();
        }

        @Test
        @DisplayName("configuração legítima pela linha de comando e em <properties> é aceita")
        void legitimosPelaLinhaDeComando() {
            reactor.escrever("sistema.properties", LEGITIMOS);
            reactor.propriedades.put(DRY_RUN, "false");
            reactor.propriedades.put("junit.platform.discovery.listener.default", "abortOnFailure");
            reactor.propriedades.put("argLine", comoArgLine(LEGITIMOS));
            reactor.propriedades.put("surefire.systemPropertiesFile", "sistema.properties");
            reactor.substituir("pom.xml", "<modules>", "<properties><argLine>" + comoArgLine(LEGITIMOS) + "</argLine></properties><modules>");
            assertThat(violacoes()).isEmpty();
        }

        @Test
        @DisplayName("configurationParameters sem filtro é aceito, sem violação intermediária")
        void configurationParametersSemFiltro() {
            reactor.substituir("pom.xml", "<argLine>@{argLine}</argLine>", "<argLine>@{argLine}</argLine><properties>"
                    + "<configurationParameters>" + LEGITIMOS + "</configurationParameters></properties>");
            assertThat(violacoes()).isEmpty();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"configurationParameters", "configurationParameters como property", "systemPropertyVariables",
                "systemProperties", "systemProperties como elemento", "argLine", "systemPropertiesFile",
                "junit-platform.properties de teste", "junit-platform.properties principal"})
        @DisplayName("dry-run ativo é recusado em cada canal")
        void dryRunRecusado(String canal) {
            String prefixo = declarar(canal, DRY_RUN + "=true\n");
            assertThat(violacoes()).containsExactly(prefixo + OMITE + DRY_RUN + "=true" + FIM);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"configurationParameters", "configurationParameters como property", "systemProperties como elemento",
                "junit-platform.properties de teste"})
        @DisplayName("listener de descoberta que só registra falha é recusado")
        void listenerDeDescobertaTolerante(String canal) {
            String prefixo = declarar(canal, "junit.platform.discovery.listener.default=logging\n");
            assertThat(violacoes()).containsExactly(prefixo + TOLERA + "junit.platform.discovery.listener.default=logging" + FIM);
        }

        @Test
        @DisplayName("dry-run com valor TRUE em maiúsculas também é ativo")
        void dryRunMaiusculas() {
            String prefixo = declarar("configurationParameters", DRY_RUN + "=TRUE\n");
            assertThat(violacoes()).containsExactly(prefixo + OMITE + DRY_RUN + "=TRUE" + FIM);
        }

        @Test
        @DisplayName("dry-run e listener tolerante pela linha de comando")
        void pelaLinhaDeComando() {
            reactor.propriedades.put(DRY_RUN, "true");
            reactor.propriedades.put("junit.platform.discovery.listener.default", "logging");
            assertThat(violacoes()).containsExactly(
                    "linha de comando: " + OMITE + DRY_RUN + "=true" + FIM,
                    "linha de comando: " + TOLERA + "junit.platform.discovery.listener.default=logging" + FIM);
        }

        @Test
        @DisplayName("dry-run por -DargLine e por -Dsurefire.systemPropertiesFile")
        void pelaLinhaDeComandoPorCanal() {
            reactor.escrever("sistema.properties", DRY_RUN + "=true\n");
            reactor.propriedades.put("argLine", "-javaagent:agente.jar -D" + DRY_RUN + "=true");
            reactor.propriedades.put("surefire.systemPropertiesFile", "sistema.properties");
            assertThat(violacoes()).containsExactly(
                    "linha de comando (argLine): " + OMITE + DRY_RUN + "=true" + FIM,
                    "linha de comando (surefire.systemPropertiesFile) (sistema.properties): " + OMITE + DRY_RUN + "=true" + FIM);
        }

        @Test
        @DisplayName("systemPropertiesFile inexistente reprova com diagnóstico")
        void systemPropertiesFileAusente() {
            reactor.escrever("agendamento/pom.xml", SUREFIRE_DO_MODULO.formatted("<systemPropertiesFile>nao-existe.properties</systemPropertiesFile>"));
            assertThat(violacoes()).containsExactly("agendamento: maven-surefire-plugin <systemPropertiesFile>: nao-existe.properties"
                    + " nao pode ser conferido, nao e um arquivo legivel: " + raiz.resolve("agendamento").resolve("nao-existe.properties"));
        }

        @Test
        @DisplayName("systemPropertiesFile que é diretório reprova com diagnóstico")
        void systemPropertiesFileDiretorio() {
            reactor.escrever("agendamento/propriedades/marcador.txt", "x");
            reactor.escrever("agendamento/pom.xml", SUREFIRE_DO_MODULO.formatted("<systemPropertiesFile>${project.basedir}/propriedades</systemPropertiesFile>"));
            assertThat(violacoes()).containsExactly("agendamento: maven-surefire-plugin <systemPropertiesFile>: propriedades"
                    + " nao pode ser conferido, nao e um arquivo legivel: " + raiz.resolve("agendamento").resolve("propriedades"));
        }

        @Test
        @DisplayName("systemPropertiesFile com conteúdo inválido reprova com diagnóstico, sem exceção")
        void systemPropertiesFileConteudoInvalido() {
            // Escape unicode malformado: Properties.load recusa com IllegalArgumentException.
            String invalido = DRY_RUN + "=" + (char) 92 + "uZZZZ\n";
            String prefixo = declarar("systemPropertiesFile", invalido);
            List<String> violacoes = violacoes();
            assertThat(violacoes).hasSize(1);
            assertThat(violacoes.getFirst()).startsWith(prefixo + "conteudo nao pode ser conferido, nao e um arquivo de propriedades valido: ");
        }

        @Test
        @DisplayName("junit-platform.properties e configurationParameters com conteúdo inválido reprovam com diagnóstico")
        void outrosCanaisComConteudoInvalido() {
            String invalido = "junit.jupiter.execution.parallel.enabled=" + (char) 92 + "u00G1\n";
            String prefixoArquivo = declarar("junit-platform.properties de teste", invalido);
            reactor.substituir("pom.xml", "<argLine>@{argLine}</argLine>", "<argLine>@{argLine}</argLine><properties>"
                    + "<configurationParameters>" + invalido + "</configurationParameters></properties>");
            List<String> violacoes = violacoes();
            assertThat(violacoes).hasSize(2);
            assertThat(violacoes.get(0)).startsWith("raiz: maven-surefire-plugin <configurationParameters>: conteudo nao pode ser conferido");
            assertThat(violacoes.get(1)).startsWith(prefixoArquivo + "conteudo nao pode ser conferido");
        }

        @Test
        @DisplayName("systemPropertiesFile pela linha de comando com caminho inválido reprova com diagnóstico")
        void systemPropertiesFileCaminhoInvalido() {
            String caminho = "sistema" + (char) 0 + ".properties";
            reactor.propriedades.put("failsafe.systemPropertiesFile", caminho);
            assertThat(violacoes()).containsExactly(
                    "linha de comando (failsafe.systemPropertiesFile): caminho invalido, o canal nao pode ser conferido: " + caminho);
        }

        @Test
        @DisplayName("canal de linha de comando não repassado à auditoria é recusado")
        void canalNaoRepassado() {
            reactor.propriedades.remove("argLine");
            assertThat(violacoes()).containsExactly("propriedade argLine nao repassada a auditoria: o canal nao pode ser conferido");
        }
    }

    @Nested
    @DisplayName("omissão mascarada pela soma de casos")
    class OmissaoMascarada {

        private static final String CLASSE = "br.com.fiap.hospital.agendamento.dominio.OmissaoMascaradaTest";
        private static final String FONTE = FontesDeAuditoria.AG + "dominio/OmissaoMascaradaTest.java";

        @BeforeEach
        void classeComDoisMetodosEUmOmitido() {
            reactor.escrever(FONTE, """
                    package br.com.fiap.hospital.agendamento.dominio;

                    import org.junit.jupiter.api.Test;
                    import org.junit.jupiter.params.ParameterizedTest;
                    import org.junit.jupiter.params.provider.ValueSource;

                    class OmissaoMascaradaTest {

                        @ParameterizedTest
                        @ValueSource(ints = {1, 2, 3, 4, 5})
                        void metodoParametrizado(int valor) { }

                        @Test
                        void metodoOmitido() { }
                    }
                    """);
            // So os cinco casos do parametrizado executaram: o metodoOmitido nao deixou caso.
            reactor.relatorio("agendamento", Plugin.SUREFIRE, CLASSE, 5);
        }

        @Test
        @DisplayName("sem mecanismo de omissão, a soma sozinha não reprova: por isso não se afirma garantia por método")
        void somaSozinhaNaoReprova() {
            assertThat(violacoes()).isEmpty();
        }

        @Test
        @DisplayName("omissão por -Dtest=Classe#metodoParametrizado reprova pelo filtro")
        void omissaoPorFiltro() {
            reactor.propriedades.put("test", "OmissaoMascaradaTest#metodoParametrizado");
            assertThat(violacoes()).containsExactly("filtro ativo por parametro (selecao por nome): "
                    + "-Dtest=OmissaoMascaradaTest#metodoParametrizado: o gate global nao aceita filtro");
        }

        @Test
        @DisplayName("omissão por @Disabled reprova pela varredura de fonte")
        void omissaoPorDisabled() {
            reactor.substituir(FONTE, "    @Test\n    void metodoOmitido", "    @org.junit.jupiter.api.Disabled\n    @Test\n    void metodoOmitido");
            assertThat(violacoes()).containsExactly("desabilitacao declarada no codigo de teste: " + FONTE
                    + ":13: org.junit.jupiter.api.Disabled em " + CLASSE + "#metodoOmitido");
        }

        @Test
        @DisplayName("parametrizado sem argumentos falha no JUnit e o erro reprova")
        void parametrizadoSemArgumentos() {
            reactor.relatorioCom("agendamento", Plugin.SUREFIRE, CLASSE, 1, "tests=\"1\" errors=\"1\" skipped=\"0\" failures=\"0\"",
                    "<error message=\"Configuration error: You must configure at least one set of arguments\""
                            + " type=\"org.junit.platform.commons.PreconditionViolationException\"/>");
            assertThat(violacoes()).containsExactly(
                    "relatorio com errors=1: " + AG_SUREFIRE + "/TEST-" + CLASSE + ".xml",
                    "caso com <error>: " + AG_SUREFIRE + "/TEST-" + CLASSE + ".xml: " + CLASSE + "#caso0");
        }
    }

    @Nested
    @DisplayName("@TestFactory")
    class Fabrica {

        @Test
        @DisplayName("fábrica com casos é suíte aceita")
        void fabricaComCasosAceita() {
            assertThat(reactor.auditar().suites()).extracting(Suite::nome).contains(FABRICA);
            assertThat(violacoes()).isEmpty();
        }

        @Test
        @DisplayName("fábrica que emite zero numa suíte só de fábrica reprova por família zero")
        void fabricaVaziaSozinhaReprova() {
            reactor.relatorio("agendamento", Plugin.SUREFIRE, FABRICA, 0);
            assertThat(violacoes()).containsExactly("familia sem casos executados: " + AG_SUREFIRE + ": " + FABRICA + " tem total zero");
        }

        @Test
        @DisplayName("limite residual declarado: fábrica vazia numa suíte com outros casos não é detectável por família")
        void limiteResidualDaFabricaVazia() {
            reactor.substituir(FontesDeAuditoria.AG + "dominio/FabricaDinamicaTest.java", "class FabricaDinamicaTest {",
                    "class FabricaDinamicaTest {\n\n    @org.junit.jupiter.api.Test\n    void ordinario() { }\n");
            // So o metodo ordinario deixou caso; a fabrica emitiu zero e nada acusa.
            reactor.relatorio("agendamento", Plugin.SUREFIRE, FABRICA, 1);
            assertThat(violacoes()).isEmpty();
        }
    }

    @Nested
    @DisplayName("invocação pelo exec do build")
    class Invocacao {

        private int executar(ByteArrayOutputStream saida, List<String> args) {
            return AuditoriaDeExecucao.executar(args.toArray(String[]::new), new PrintStream(saida, true, StandardCharsets.UTF_8));
        }

        private List<String> argumentos() {
            List<String> args = new ArrayList<>(List.of(raiz.toString(), ReactorDeAuditoria.SESSAO));
            reactor.propriedades.forEach((nome, valor) -> args.add(nome + "=" + valor));
            return args;
        }

        @Test
        @DisplayName("aprovada sai com zero e imprime suítes, relatórios e casos por módulo")
        void aprovadaSaiComZero() {
            ByteArrayOutputStream saida = new ByteArrayOutputStream();
            assertThat(executar(saida, argumentos())).isZero();
            assertThat(saida.toString(StandardCharsets.UTF_8)).contains("agendamento", "seguranca", "total",
                    "Auditoria de execucao: APROVADA");
        }

        @Test
        @DisplayName("reprovada sai com um e lista as violações")
        void reprovadaSaiComUm() {
            reactor.propriedades.put("skipITs", "true");
            ByteArrayOutputStream saida = new ByteArrayOutputStream();
            assertThat(executar(saida, argumentos())).isEqualTo(1);
            assertThat(saida.toString(StandardCharsets.UTF_8)).contains("Auditoria de execucao: REPROVADA", "-DskipITs=true");
        }

        @Test
        @DisplayName("invocação inválida sai com dois")
        void invocacaoInvalidaSaiComDois() {
            assertThat(executar(new ByteArrayOutputStream(), List.of(raiz.toString()))).isEqualTo(2);
            assertThat(executar(new ByteArrayOutputStream(), List.of(raiz.toString(), "s", "semIgual"))).isEqualTo(2);
        }
    }
}
