package br.com.fiap.hospital.qualidade;

import br.com.fiap.hospital.qualidade.InventarioDeTestes.Plugin;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Reactor sintetico para a auditoria, modelado nas estruturas reais:
 *
 * <ul>
 *   <li>{@code agendamento}: o contrato abstrato {@code ConsultaRepositoryContractTest} com tres
 *       {@code @Nested}, executado por {@code ConsultaRepositoryFakeTest} no Surefire e por
 *       {@code ConsultaRepositoryAdapterIT} no Failsafe, este com relatorio externo zero; o
 *       {@code GraphiqlPorProfileIT} com tres {@code @Nested}; uma classe parametrizada; uma
 *       {@code @TestFactory}; base abstrata, auxiliar, interface, enum, record e anotacao;</li>
 *   <li>{@code seguranca}: o {@code JwtServiceTest} com tres {@code @Nested} e externo zero, sem IT.</li>
 * </ul>
 *
 * <p>Os relatorios, o {@code failsafe-summary.xml}, arquivos {@code *.txt} e um {@code .dumpstream}
 * sao gravados como o plugin grava. Cada negativo muda so a causa que quer provar.
 */
final class ReactorDeAuditoria {

    static final String SESSAO = "2026-09-13T10:00:00.000Z";
    static final String AG = "br.com.fiap.hospital.agendamento";
    static final String CONTRATO = AG + ".contrato.ConsultaRepositoryContractTest";
    static final String FAKE = AG + ".contrato.ConsultaRepositoryFakeTest";
    static final String ADAPTER = AG + ".integracao.ConsultaRepositoryAdapterIT";
    static final String GRAPHIQL = AG + ".integracao.GraphiqlPorProfileIT";
    static final String PARAMETRIZADA = AG + ".dominio.PeriodoParametrizadoTest";
    static final String FABRICA = AG + ".dominio.FabricaDinamicaTest";
    static final String JWT = "br.com.fiap.hospital.security.JwtServiceTest";

    final Path raiz;
    final Map<String, String> propriedades = new LinkedHashMap<>();

    ReactorDeAuditoria(Path raiz) {
        this.raiz = raiz;
        PropriedadesDeFiltro.todas().forEach(p -> propriedades.put(p, "${" + p + "}"));
        ParametrosDoJUnitPlatform.CANAIS_DE_LINHA_DE_COMANDO.forEach(p -> propriedades.put(p, "${" + p + "}"));
    }

    /** A arvore aprovada: toda suite com sua familia verde. */
    static ReactorDeAuditoria padrao(Path raiz) {
        ReactorDeAuditoria reactor = new ReactorDeAuditoria(raiz);
        reactor.escrever("pom.xml", POM_RAIZ);
        reactor.escrever("agendamento/pom.xml", "<project><artifactId>agendamento</artifactId></project>");
        reactor.escrever("seguranca/pom.xml", "<project><artifactId>seguranca</artifactId></project>");
        reactor.escrever("agendamento/src/main/java/br/com/fiap/hospital/agendamento/dominio/Consulta.java",
                "package br.com.fiap.hospital.agendamento.dominio; public class Consulta { }");
        reactor.escrever(SessaoDeVerificacao.MARCADOR.toString(), SESSAO);
        FontesDeAuditoria.PADRAO.forEach((caminho, fonte) -> reactor.escrever(caminho, fonte));

        reactor.relatorio("agendamento", Plugin.SUREFIRE, FAKE, 0);
        reactor.contrato("agendamento", Plugin.SUREFIRE);
        reactor.relatorio("agendamento", Plugin.SUREFIRE, PARAMETRIZADA, 6);
        reactor.relatorio("agendamento", Plugin.SUREFIRE, FABRICA, 3);
        reactor.relatorio("agendamento", Plugin.FAILSAFE, ADAPTER, 0);
        reactor.contrato("agendamento", Plugin.FAILSAFE);
        reactor.relatorio("agendamento", Plugin.FAILSAFE, GRAPHIQL, 0);
        for (String profile : new String[] {"ProfilePadrao", "ProfileDev", "ProfileDemo"}) {
            reactor.relatorio("agendamento", Plugin.FAILSAFE, GRAPHIQL + "$" + profile, 1);
        }
        reactor.sumario("agendamento", "null", 8, 0, 0, 0);
        reactor.relatorio("seguranca", Plugin.SUREFIRE, JWT, 0);
        reactor.relatorio("seguranca", Plugin.SUREFIRE, JWT + "$Emissao", 2);
        reactor.relatorio("seguranca", Plugin.SUREFIRE, JWT + "$Recusa", 2);
        reactor.relatorio("seguranca", Plugin.SUREFIRE, JWT + "$Configuracao", 1);
        reactor.sumario("seguranca", "254", 0, 0, 0, 0);

        // Arquivos dos plugins que nao sao relatorios de suite: ignorados pelas familias.
        reactor.escrever("agendamento/target/failsafe-reports/" + ADAPTER + ".txt", "Tests run: 0");
        reactor.escrever("agendamento/target/failsafe-reports/" + ADAPTER + "-output.txt", "saida");
        reactor.escrever("agendamento/target/failsafe-reports/2026-09-13T10-00-00_000-jvmRun1.dumpstream", "fluxo");
        reactor.escrever("seguranca/target/surefire-reports/" + JWT + "$Emissao.txt", "Tests run: 2");
        return reactor;
    }

    private void contrato(String modulo, Plugin plugin) {
        relatorio(modulo, plugin, CONTRATO + "$Listagem", 2);
        relatorio(modulo, plugin, CONTRATO + "$GravacaoERecuperacao", 2);
        relatorio(modulo, plugin, CONTRATO + "$DeteccaoDeConflito", 1);
    }

    AuditoriaDeExecucao.Resultado auditar() {
        return AuditoriaDeExecucao.auditar(raiz, SESSAO, propriedades);
    }

    Path diretorio(String modulo, Plugin plugin) {
        return raiz.resolve(modulo).resolve("target").resolve(plugin.diretorio);
    }

    Path relatorioDe(String modulo, Plugin plugin, String nome) {
        return diretorio(modulo, plugin).resolve("TEST-" + nome + ".xml");
    }

    void relatorio(String modulo, Plugin plugin, String nome, int casos) {
        relatorioCom(modulo, plugin, nome, casos, "tests=\"" + casos + "\" errors=\"0\" skipped=\"0\" failures=\"0\"", "");
    }

    /** Relatorio com atributos e conteudo de caso dados; os casos sao gerados como o plugin gera. */
    void relatorioCom(String modulo, Plugin plugin, String nome, int casos, String atributos, String dentroDoPrimeiroCaso) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<testsuite xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" version=\"3.0\" name=\"")
                .append(nome).append("\" time=\"0.1\" ").append(atributos).append(">\n")
                .append("  <properties><property name=\"java.version\" value=\"21\"/></properties>\n");
        for (int i = 0; i < casos; i++) {
            xml.append("  <testcase name=\"caso").append(i).append("\" classname=\"").append(nome).append("\" time=\"0.01\"");
            if (i == 0 && !dentroDoPrimeiroCaso.isEmpty()) {
                xml.append(">").append(dentroDoPrimeiroCaso).append("</testcase>\n");
            } else {
                xml.append("/>\n");
            }
        }
        escreverEm(relatorioDe(modulo, plugin, nome), xml.append("</testsuite>\n").toString());
    }

    void sumario(String modulo, String resultado, int completos, int erros, int falhas, int pulados) {
        escreverEm(diretorio(modulo, Plugin.FAILSAFE).resolve(FamiliasDeRelatorios.SUMARIO),
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<failsafe-summary xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
                        + " result=\"" + resultado + "\" timeout=\"false\">\n    <completed>" + completos + "</completed>\n"
                        + "    <errors>" + erros + "</errors>\n    <failures>" + falhas + "</failures>\n"
                        + "    <skipped>" + pulados + "</skipped>\n"
                        + "    <failureMessage xsi:nil=\"true\"/>\n</failsafe-summary>\n");
    }

    void escrever(String relativo, String conteudo) {
        escreverEm(raiz.resolve(relativo), conteudo);
    }

    String ler(String relativo) {
        try {
            return Files.readString(raiz.resolve(relativo), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void substituir(String relativo, String de, String para) {
        String atual = ler(relativo);
        if (!atual.contains(de)) {
            throw new IllegalStateException("trecho ausente em " + relativo + ": " + de);
        }
        escrever(relativo, atual.replace(de, para));
    }

    void apagar(Path caminho) {
        try {
            if (Files.isDirectory(caminho)) {
                try (Stream<Path> todos = Files.walk(caminho)) {
                    for (Path p : todos.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(p);
                    }
                }
            } else {
                Files.delete(caminho);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void escreverEm(Path arquivo, String conteudo) {
        try {
            Files.createDirectories(arquivo.getParent());
            Files.writeString(arquivo, conteudo, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static final String POM_RAIZ = """
            <project>
                <groupId>br.com.fiap.hospital</groupId>
                <artifactId>raiz</artifactId>
                <modules>
                    <module>agendamento</module>
                    <module>seguranca</module>
                </modules>
                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-surefire-plugin</artifactId>
                            <configuration>
                                <includes>
                                    <include>**/*Test.java</include>
                                </includes>
                                <argLine>@{argLine}</argLine>
                            </configuration>
                        </plugin>
                        <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-failsafe-plugin</artifactId>
                            <configuration>
                                <includes>
                                    <include>**/*IT.java</include>
                                </includes>
                            </configuration>
                            <executions>
                                <execution>
                                    <id>integration-tests</id>
                                    <goals>
                                        <goal>integration-test</goal>
                                        <goal>verify</goal>
                                    </goals>
                                </execution>
                            </executions>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """;
}
