package br.com.fiap.hospital.qualidade;

import static br.com.fiap.hospital.qualidade.WorkspaceSintetico.APPLICATION;
import static br.com.fiap.hospital.qualidade.WorkspaceSintetico.DOMAIN;
import static br.com.fiap.hospital.qualidade.WorkspaceSintetico.SESSAO;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** O gate de cobertura do D4 sobre reactors sinteticos, um negativo por causa. */
@DisplayName("Verificador de cobertura")
class VerificadorDeCoberturaTest {

    @TempDir
    Path raiz;

    private VerificadorDeCobertura.Resultado verificar(WorkspaceSintetico workspace) {
        return VerificadorDeCobertura.verificar(workspace.gravar(), raiz, SESSAO);
    }

    private VerificadorDeCobertura.Resultado verificarRelatorio(String conteudo) {
        return VerificadorDeCobertura.verificar(new WorkspaceSintetico(raiz).gravar(conteudo), raiz, SESSAO);
    }

    private WorkspaceSintetico base() {
        return WorkspaceSintetico.acimaDosPisos(raiz);
    }

    /** Cinco groups com 40/10 cada fora do agendamento, e o agendamento exatamente nos pisos. */
    private WorkspaceSintetico noPiso(long cobertasContracts, long perdidasContracts,
            long cobertasModel, long perdidasModel, long cobertasApplication, long perdidasApplication) {
        return new WorkspaceSintetico(raiz)
                .pacote("shared-contracts", "br/com/fiap/hospital/contracts", cobertasContracts, perdidasContracts)
                .pacote("shared-security", "br/com/fiap/hospital/security", 40, 10)
                .pacote("agendamento-service", DOMAIN, 50, 5)
                .pacote("agendamento-service", DOMAIN + "/model", cobertasModel, perdidasModel)
                .pacote("agendamento-service", APPLICATION, cobertasApplication, perdidasApplication)
                .pacote("notificacao-service", "br/com/fiap/hospital/notificacao", 40, 10)
                .pacote("historico-service", "br/com/fiap/hospital/historico", 40, 10);
    }

    private static String bloco(String xml, String abertura, String fechamento) {
        int inicio = xml.indexOf(abertura);
        return xml.substring(inicio, xml.indexOf(fechamento, inicio) + fechamento.length());
    }

    /** Remove o contador LINE direto do group, que e o ultimo antes do fechamento. */
    private static String semLineDoGrupo(String xml, String grupo) {
        String original = bloco(xml, "<group name=\"" + grupo + "\">", "</group>");
        int line = original.lastIndexOf("<counter type=\"LINE\"");
        String alterado = original.substring(0, line) + original.substring(original.indexOf("/>", line) + 2);
        return xml.replace(original, alterado);
    }

    @Nested
    @DisplayName("pisos e escopos")
    class Pisos {

        @Test
        @DisplayName("Scenario: Cobertura acima dos pisos é aceita")
        void acimaDosPisosEAceita() {
            VerificadorDeCobertura.Resultado resultado = verificar(base());
            assertThat(resultado.violacoes()).isEmpty();
            assertThat(resultado.tabela()).hasSize(5);
            assertThat(resultado.tabela().get(1)).contains("global", "842", "108", "88.63%", "85%", "ok");
            assertThat(resultado.tabela().get(2)).contains("domain", "140", "10", "93.33%", "90%", "ok");
            assertThat(resultado.tabela().get(3)).contains("application", "92", "8", "92.00%", "90%", "ok");
            // INSTRUCTION e impresso so para transparencia; BRANCH ausente nao reprova.
            assertThat(resultado.tabela().get(4)).contains("global INSTRUCTION", "informativo");
        }

        @Test
        @DisplayName("Scenario: Escopo exatamente no piso é aceito")
        void exatamenteNoPisoEAceito() {
            VerificadorDeCobertura.Resultado resultado = verificar(noPiso(40, 10, 40, 5, 90, 10));
            assertThat(resultado.violacoes()).isEmpty();
            assertThat(resultado.tabela().get(1)).contains("340", "60", "85.00%");
            assertThat(resultado.tabela().get(2)).contains("90", "10", "90.00%");
            assertThat(resultado.tabela().get(3)).contains("90", "10", "90.00%");
        }

        @Test
        @DisplayName("uma linha abaixo do piso global reprova")
        void umaLinhaAbaixoDoPisoGlobalReprova() {
            assertThat(verificar(noPiso(39, 11, 40, 5, 90, 10)).violacoes()).containsExactly(
                    "cobertura LINE global abaixo do piso: 84.75% < 85% (cobertas=339, perdidas=61)");
        }

        @Test
        @DisplayName("uma linha abaixo do piso de domain reprova")
        void umaLinhaAbaixoDoPisoDeDomainReprova() {
            assertThat(verificar(noPiso(41, 9, 39, 6, 90, 10)).violacoes()).containsExactly(
                    "cobertura LINE domain abaixo do piso: 89.00% < 90% (cobertas=89, perdidas=11)");
        }

        @Test
        @DisplayName("uma linha abaixo do piso de application reprova")
        void umaLinhaAbaixoDoPisoDeApplicationReprova() {
            assertThat(verificar(noPiso(41, 9, 40, 5, 89, 11)).violacoes()).containsExactly(
                    "cobertura LINE application abaixo do piso: 89.00% < 90% (cobertas=89, perdidas=11)");
        }

        @Test
        @DisplayName("Scenario: Cobertura global abaixo do piso falha o build")
        void globalAbaixoReprova() {
            WorkspaceSintetico workspace = base();
            workspace.semPacotes("notificacao-service").pacote("notificacao-service", "br/com/fiap/hospital/notificacao", 50, 150);
            assertThat(verificar(workspace).violacoes()).containsExactly(
                    "cobertura LINE global abaixo do piso: 76.00% < 85% (cobertas=722, perdidas=228)");
        }

        @Test
        @DisplayName("Scenario: Subárvore domain abaixo do piso falha mesmo com cobertura global suficiente")
        void domainAbaixoReprova() {
            WorkspaceSintetico workspace = base();
            workspace.grupos.get("agendamento-service").set(0,
                    new WorkspaceSintetico.Pacote(DOMAIN, BigInteger.valueOf(70), BigInteger.valueOf(30)));
            VerificadorDeCobertura.Resultado resultado = verificar(workspace);
            assertThat(resultado.violacoes()).containsExactly(
                    "cobertura LINE domain abaixo do piso: 76.66% < 90% (cobertas=115, perdidas=35)");
            assertThat(resultado.tabela().get(1)).contains("86.00%", "ok");
        }

        @Test
        @DisplayName("Scenario: Subárvore application abaixo do piso falha mesmo com cobertura global suficiente")
        void applicationAbaixoReprova() {
            WorkspaceSintetico workspace = base();
            workspace.grupos.get("agendamento-service").set(2,
                    new WorkspaceSintetico.Pacote(APPLICATION, BigInteger.valueOf(70), BigInteger.valueOf(30)));
            VerificadorDeCobertura.Resultado resultado = verificar(workspace);
            assertThat(resultado.violacoes()).containsExactly(
                    "cobertura LINE application abaixo do piso: 70.00% < 90% (cobertas=70, perdidas=30)");
            assertThat(resultado.tabela().get(1)).contains("86.31%", "ok");
        }

        @Test
        @DisplayName("subpacote conta no escopo; pacote de nome parecido, não")
        void subpacoteContaENomeParecidoNao() {
            WorkspaceSintetico workspace = base()
                    .pacote("agendamento-service", DOMAIN + "x", 0, 10)
                    .pacote("agendamento-service", APPLICATION + "/port/saida", 10, 0);
            VerificadorDeCobertura.Resultado resultado = verificar(workspace);
            assertThat(resultado.violacoes()).isEmpty();
            assertThat(resultado.tabela().get(2)).contains("140", "10", "93.33%");
            assertThat(resultado.tabela().get(3)).contains("102", "8", "92.72%");
        }
    }

    @Nested
    @DisplayName("Scenario: Percentual é calculado por soma, e não por média")
    class SomaEMedia {

        private static double media(double... percentuais) {
            return java.util.Arrays.stream(percentuais).average().orElseThrow();
        }

        @Test
        @DisplayName("média dos groups acima do piso, soma abaixo: reprova")
        void mediaAcimaSomaAbaixoReprova() {
            WorkspaceSintetico workspace = new WorkspaceSintetico(raiz)
                    .pacote("shared-contracts", "br/com/fiap/hospital/contracts", 10, 0)
                    .pacote("shared-security", "br/com/fiap/hospital/security", 10, 0)
                    .pacote("agendamento-service", DOMAIN, 19, 1)
                    .pacote("agendamento-service", APPLICATION, 19, 1)
                    .pacote("notificacao-service", "br/com/fiap/hospital/notificacao", 10, 0)
                    .pacote("historico-service", "br/com/fiap/hospital/historico", 700, 300);
            assertThat(media(100, 100, 95, 100, 70)).isGreaterThanOrEqualTo(85);
            assertThat(verificar(workspace).violacoes()).containsExactly(
                    "cobertura LINE global abaixo do piso: 71.77% < 85% (cobertas=768, perdidas=302)");
        }

        @Test
        @DisplayName("média dos groups abaixo do piso, soma acima: aceita")
        void mediaAbaixoSomaAcimaAceita() {
            WorkspaceSintetico workspace = new WorkspaceSintetico(raiz)
                    .pacote("shared-contracts", "br/com/fiap/hospital/contracts", 1, 1)
                    .pacote("shared-security", "br/com/fiap/hospital/security", 1, 1)
                    .pacote("agendamento-service", DOMAIN, 500, 25)
                    .pacote("agendamento-service", APPLICATION, 500, 25)
                    .pacote("notificacao-service", "br/com/fiap/hospital/notificacao", 1, 1)
                    .pacote("historico-service", "br/com/fiap/hospital/historico", 1, 1);
            assertThat(media(50, 50, 95.23, 50, 50)).isLessThan(85);
            VerificadorDeCobertura.Resultado resultado = verificar(workspace);
            assertThat(resultado.violacoes()).isEmpty();
            assertThat(resultado.tabela().get(1)).contains("1004", "54", "94.89%");
        }

        @Test
        @DisplayName("média dos pacotes de domain acima do piso, soma abaixo: reprova")
        void mediaDosPacotesAcimaSomaAbaixoReprova() {
            WorkspaceSintetico workspace = base();
            List<WorkspaceSintetico.Pacote> agendamento = workspace.grupos.get("agendamento-service");
            agendamento.set(0, new WorkspaceSintetico.Pacote(DOMAIN, BigInteger.ONE, BigInteger.ZERO));
            agendamento.set(1, new WorkspaceSintetico.Pacote(DOMAIN + "/model", BigInteger.valueOf(80), BigInteger.valueOf(20)));
            assertThat(media(100, 80)).isGreaterThanOrEqualTo(90);
            assertThat(verificar(workspace).violacoes()).containsExactly(
                    "cobertura LINE domain abaixo do piso: 80.19% < 90% (cobertas=81, perdidas=20)");
        }

        @Test
        @DisplayName("média dos pacotes de domain abaixo do piso, soma acima: aceita")
        void mediaDosPacotesAbaixoSomaAcimaAceita() {
            WorkspaceSintetico workspace = base();
            List<WorkspaceSintetico.Pacote> agendamento = workspace.grupos.get("agendamento-service");
            agendamento.set(0, new WorkspaceSintetico.Pacote(DOMAIN, BigInteger.ZERO, BigInteger.ONE));
            agendamento.set(1, new WorkspaceSintetico.Pacote(DOMAIN + "/model", BigInteger.valueOf(950), BigInteger.TEN));
            assertThat(media(0, 98.95)).isLessThan(90);
            VerificadorDeCobertura.Resultado resultado = verificar(workspace);
            assertThat(resultado.violacoes()).isEmpty();
            assertThat(resultado.tabela().get(2)).contains("950", "11", "98.85%");
        }
    }

    @Nested
    @DisplayName("Scenario: Relatório ausente, vazio ou ilegível falha fechado")
    class RelatorioIlegivel {

        @Test
        @DisplayName("relatório ausente")
        void ausente() throws IOException {
            WorkspaceSintetico workspace = base();
            Path relatorio = workspace.gravar();
            Files.delete(relatorio);
            assertThat(VerificadorDeCobertura.verificar(relatorio, raiz, SESSAO).violacoes())
                    .containsExactly("relatorio de cobertura recusado: arquivo ausente: " + relatorio);
        }

        @Test
        @DisplayName("relatório vazio")
        void vazio() {
            Path relatorio = base().relatorio();
            assertThat(verificarRelatorio("").violacoes())
                    .containsExactly("relatorio de cobertura recusado: arquivo vazio: " + relatorio);
        }

        @Test
        @DisplayName("relatório só com espaços")
        void soEspacos() {
            assertThat(verificarRelatorio(" \n\t ").violacoes())
                    .containsExactly("relatorio de cobertura recusado: documento vazio");
        }

        @Test
        @DisplayName("XML inválido")
        void xmlInvalido() {
            List<String> violacoes = verificarRelatorio("<report><group name=\"shared-contracts\">").violacoes();
            assertThat(violacoes).hasSize(1);
            assertThat(violacoes.getFirst()).startsWith("relatorio de cobertura recusado: documento ilegivel:");
        }

        @Test
        @DisplayName("bytes que não são UTF-8")
        void bytesInvalidos() throws IOException {
            Path relatorio = new WorkspaceSintetico(raiz).gravar("x");
            Files.write(relatorio, new byte[] {'<', 'r', '>', (byte) 0xC3, (byte) 0x28, '<', '/', 'r', '>'});
            assertThat(VerificadorDeCobertura.verificar(relatorio, raiz, SESSAO).violacoes())
                    .containsExactly("relatorio de cobertura recusado: arquivo ilegivel: " + relatorio);
        }

        @Test
        @DisplayName("<report/> sem groups")
        void reportVazio() {
            assertThat(verificarRelatorio("<report name=\"quality-gates\"/>").violacoes())
                    .containsExactly("relatorio sem groups: o agregado precisa de um por modulo de codigo");
        }

        @Test
        @DisplayName("documento que não é um report")
        void naoEReport() {
            assertThat(verificarRelatorio("<html><body/></html>").violacoes())
                    .containsExactly("relatorio sem elemento report: raiz e html");
        }

        @Test
        @DisplayName("index.html do agregado ausente")
        void indiceAusente() throws IOException {
            Path relatorio = base().gravar();
            Files.delete(relatorio.resolveSibling("index.html"));
            assertThat(VerificadorDeCobertura.verificar(relatorio, raiz, SESSAO).violacoes()).containsExactly(
                    "index.html do relatorio agregado ausente ou vazio: " + relatorio.resolveSibling("index.html"));
        }
    }

    @Nested
    @DisplayName("Scenario: Módulo, pacote, contador ou dados de execução ausentes falham fechado")
    class EstruturaAusente {

        @Test
        @DisplayName("group ausente")
        void grupoAusente() {
            WorkspaceSintetico workspace = base();
            workspace.grupos.remove("notificacao-service");
            assertThat(verificar(workspace).violacoes()).containsExactly("group ausente: notificacao-service");
        }

        @Test
        @DisplayName("group duplicado")
        void grupoDuplicado() {
            String xml = base().xml();
            String historico = bloco(xml, "<group name=\"historico-service\">", "</group>");
            assertThat(verificarRelatorio(xml.replace(historico, historico + historico)).violacoes())
                    .containsExactly("group duplicado: historico-service");
        }

        @Test
        @DisplayName("group do próprio quality-gates é inesperado")
        void grupoInesperado() {
            WorkspaceSintetico workspace = base().pacote("quality-gates", "br/com/fiap/hospital/qualidade", 10, 0);
            assertThat(verificar(workspace).violacoes()).containsExactly("group inesperado: quality-gates");
        }

        @Test
        @DisplayName("group sem pacotes")
        void grupoSemPacotes() {
            assertThat(verificar(base().semPacotes("shared-security")).violacoes())
                    .containsExactly("group shared-security sem pacotes");
        }

        @Test
        @DisplayName("pacote de domain ausente")
        void pacoteDeDomainAusente() {
            WorkspaceSintetico workspace = base();
            workspace.grupos.get("agendamento-service").removeIf(p -> p.nome().startsWith(DOMAIN));
            workspace.pacote("agendamento-service", DOMAIN + "x", 10, 0);
            assertThat(verificar(workspace).violacoes()).containsExactly("nenhum pacote sob " + DOMAIN);
        }

        @Test
        @DisplayName("pacote de application ausente")
        void pacoteDeApplicationAusente() {
            WorkspaceSintetico workspace = base();
            workspace.grupos.get("agendamento-service").removeIf(p -> p.nome().startsWith(APPLICATION));
            assertThat(verificar(workspace).violacoes()).containsExactly("nenhum pacote sob " + APPLICATION);
        }

        @Test
        @DisplayName("contador LINE ausente no group")
        void lineAusenteNoGrupo() {
            assertThat(verificarRelatorio(semLineDoGrupo(base().xml(), "historico-service")).violacoes())
                    .containsExactly("contador LINE ausente no group historico-service");
        }

        @Test
        @DisplayName("contador LINE duplicado no group")
        void lineDuplicadoNoGrupo() {
            String xml = base().xml();
            String original = bloco(xml, "<group name=\"historico-service\">", "</group>");
            String alterado = original.replace("</group>", "<counter type=\"LINE\" missed=\"0\" covered=\"1\"/></group>");
            assertThat(verificarRelatorio(xml.replace(original, alterado)).violacoes())
                    .containsExactly("contador LINE duplicado no group historico-service");
        }

        @Test
        @DisplayName("contador LINE ausente no report")
        void lineAusenteNoReport() {
            WorkspaceSintetico workspace = base();
            workspace.totalForcado = "<counter type=\"INSTRUCTION\" missed=\"1\" covered=\"1\"/>";
            assertThat(verificar(workspace).violacoes()).containsExactly("contador LINE ausente no report");
        }

        @Test
        @DisplayName("contador LINE duplicado no report")
        void lineDuplicadoNoReport() {
            WorkspaceSintetico workspace = base();
            workspace.totalForcado = WorkspaceSintetico.linha(BigInteger.valueOf(842), BigInteger.valueOf(108))
                    + "<counter type=\"LINE\" missed=\"108\" covered=\"842\"/>";
            assertThat(verificar(workspace).violacoes()).containsExactly("contador LINE duplicado no report");
        }

        @Test
        @DisplayName("contador LINE ausente em pacote de domain")
        void lineAusenteNoPacote() {
            String xml = base().xml();
            String original = bloco(xml, "<package name=\"" + DOMAIN + "/model\">", "</package>");
            String alterado = original.replace("<counter type=\"LINE\" missed=\"5\" covered=\"45\"/>", "");
            assertThat(verificarRelatorio(xml.replace(original, alterado)).violacoes())
                    .containsExactly("contador LINE ausente no pacote " + DOMAIN + "/model, que tem evidencia executavel");
        }

        @Test
        @DisplayName("contador LINE ilegível ou negativo")
        void lineIlegivel() {
            String xml = base().xml();
            String negativo = xml.replace("missed=\"5\" covered=\"45\"", "missed=\"-5\" covered=\"45\"");
            assertThat(verificarRelatorio(negativo).violacoes())
                    .containsExactly("contador LINE ilegivel no pacote " + DOMAIN + "/model");
            WorkspaceSintetico workspace = base();
            workspace.totalForcado = "<counter type=\"LINE\" missed=\"cento e oito\" covered=\"842\"/>";
            assertThat(verificar(workspace).violacoes()).containsExactly("contador LINE ilegivel no report");
        }

        @Test
        @DisplayName("soma dos groups divergente do total do report")
        void somaDivergente() {
            WorkspaceSintetico workspace = base();
            workspace.totalForcado = WorkspaceSintetico.linha(BigInteger.valueOf(843), BigInteger.valueOf(107));
            assertThat(verificar(workspace).violacoes()).containsExactly(
                    "soma LINE dos groups (cobertas=842, perdidas=108) diverge do total do report (cobertas=843, perdidas=107)");
        }

        @Test
        @DisplayName("total zero")
        void totalZero() {
            WorkspaceSintetico workspace = new WorkspaceSintetico(raiz);
            WorkspaceSintetico.MODULOS.forEach(m -> workspace.pacote(m, "br/com/fiap/hospital/" + m.replace("-", ""), 0, 0));
            workspace.pacote("agendamento-service", DOMAIN, 0, 0).pacote("agendamento-service", APPLICATION, 0, 0);
            assertThat(verificar(workspace).violacoes()).containsExactly("total LINE do report e zero: relatorio sem dados");
        }

        @Test
        @DisplayName("escopo sem linhas")
        void escopoSemLinhas() {
            WorkspaceSintetico workspace = base();
            workspace.grupos.get("agendamento-service").removeIf(p -> p.nome().startsWith(APPLICATION));
            workspace.pacote("agendamento-service", APPLICATION, 0, 0);
            assertThat(verificar(workspace).violacoes()).containsExactly("escopo " + APPLICATION + " sem linhas");
        }

        @Test
        @DisplayName("jacoco.exec ausente")
        void execAusente() throws IOException {
            Path relatorio = base().gravar();
            Files.delete(raiz.resolve("agendamento-service/target/jacoco.exec"));
            assertThat(VerificadorDeCobertura.verificar(relatorio, raiz, SESSAO).violacoes())
                    .containsExactly("jacoco.exec ausente em agendamento-service");
        }

        @Test
        @DisplayName("jacoco.exec vazio")
        void execVazio() throws IOException {
            Path relatorio = base().gravar();
            Files.write(raiz.resolve("shared-contracts/target/jacoco.exec"), new byte[0]);
            assertThat(VerificadorDeCobertura.verificar(relatorio, raiz, SESSAO).violacoes())
                    .containsExactly("jacoco.exec vazio em shared-contracts");
        }

        @Test
        @DisplayName("somas grandes não transbordam")
        void somasGrandesNaoTransbordam() {
            BigInteger maximo = BigInteger.valueOf(Long.MAX_VALUE);
            WorkspaceSintetico aceito = base()
                    .pacote("historico-service", "br/com/fiap/hospital/historico/grande", maximo, BigInteger.ZERO)
                    .pacote("notificacao-service", "br/com/fiap/hospital/notificacao/grande", maximo, BigInteger.ZERO);
            VerificadorDeCobertura.Resultado resultado = verificar(aceito);
            assertThat(resultado.violacoes()).isEmpty();
            assertThat(resultado.tabela().get(1)).contains(maximo.add(maximo).add(BigInteger.valueOf(842)).toString());

            // Em long, cobertas x 100 transbordaria para negativo, e o piso seria julgado sobre lixo.
            BigInteger grande = BigInteger.TEN.pow(17);
            WorkspaceSintetico reprovado = base().pacote("historico-service", "br/com/fiap/hospital/historico/grande", grande, grande);
            List<String> violacoes = verificar(reprovado).violacoes();
            assertThat(violacoes).hasSize(1);
            assertThat(violacoes.getFirst()).startsWith("cobertura LINE global abaixo do piso: 50.00% < 85%");
        }
    }

    /** Troca o pacote inteiro, pelo bloco de abertura ate o fechamento, pelo conteudo dado. */
    private static String trocarPacote(String xml, String nome, String novo) {
        return xml.replace(bloco(xml, "<package name=\"" + nome + "\">", "</package>"), novo);
    }

    /** Troca o contador LINE direto do group, o ultimo antes do fechamento. */
    private static String trocarLineDoGrupo(String xml, String grupo, String novo) {
        String original = bloco(xml, "<group name=\"" + grupo + "\">", "</group>");
        int line = original.lastIndexOf("<counter type=\"LINE\"");
        String alterado = original.substring(0, line) + novo + original.substring(original.indexOf("/>", line) + 2);
        return xml.replace(original, alterado);
    }

    @Nested
    @DisplayName("validação de todos os packages (A+)")
    class PacotesValidados {

        /** Pacote copiado do relatorio agregado real da primeira execucao completa. */
        private static final String PORT_REAL = "<package name=\"br/com/fiap/hospital/agendamento/domain/port\">"
                + "<class name=\"br/com/fiap/hospital/agendamento/domain/port/UsuarioRepositoryPort\" sourcefilename=\"UsuarioRepositoryPort.java\"/>"
                + "<class name=\"br/com/fiap/hospital/agendamento/domain/port/EventPublisherPort\" sourcefilename=\"EventPublisherPort.java\"/>"
                + "<class name=\"br/com/fiap/hospital/agendamento/domain/port/VerificadorDeSenhaPort\" sourcefilename=\"VerificadorDeSenhaPort.java\"/>"
                + "<class name=\"br/com/fiap/hospital/agendamento/domain/port/ConsultaRepositoryPort\" sourcefilename=\"ConsultaRepositoryPort.java\"/>"
                + "<sourcefile name=\"ConsultaRepositoryPort.java\"/><sourcefile name=\"EventPublisherPort.java\"/>"
                + "<sourcefile name=\"UsuarioRepositoryPort.java\"/><sourcefile name=\"VerificadorDeSenhaPort.java\"/></package>";

        private static final String MODEL = DOMAIN + "/model";
        private static final String AUSENTE_MODEL = "contador LINE ausente no pacote " + MODEL + ", que tem evidencia executavel";

        @Test
        @DisplayName("Scenario: Pacote sem linhas executáveis contribui 0/0 — formato real do JaCoCo")
        void pacoteRealSoDeInterfacesContribuiZero() {
            String xml = base().xml();
            String infraestrutura = "<package name=\"br/com/fiap/hospital/agendamento/infrastructure\">";
            VerificadorDeCobertura.Resultado resultado = verificarRelatorio(xml.replace(infraestrutura, PORT_REAL + infraestrutura));
            assertThat(resultado.violacoes()).isEmpty();
            assertThat(resultado.tabela().get(1)).contains("842", "108", "88.63%");
            assertThat(resultado.tabela().get(2)).contains("140", "10", "93.33%");
        }

        @Test
        @DisplayName("os três pacotes 0/0 do relatório real, com os números reais, são aceitos")
        void tresPacotesReaisSaoAceitos() {
            WorkspaceSintetico workspace = new WorkspaceSintetico(raiz)
                    .pacote("shared-contracts", "br/com/fiap/hospital/contracts", 174, 7)
                    .pacote("shared-security", "br/com/fiap/hospital/security", 127, 0)
                    .pacote("agendamento-service", "br/com/fiap/hospital/agendamento/infrastructure/web", 509, 18)
                    .pacote("agendamento-service", DOMAIN, 309, 4)
                    .pacote("agendamento-service", APPLICATION, 158, 0)
                    .pacoteDeInterfaces("agendamento-service", DOMAIN + "/port",
                            "UsuarioRepositoryPort", "EventPublisherPort", "VerificadorDeSenhaPort", "ConsultaRepositoryPort")
                    .pacote("notificacao-service", "br/com/fiap/hospital/notificacao/service", 183, 2)
                    .pacoteDeInterfaces("notificacao-service", "br/com/fiap/hospital/notificacao/repository",
                            "EventoProcessadoRepository", "AgendaLocalRepository", "NotificacaoEnviadaRepository")
                    .pacote("historico-service", "br/com/fiap/hospital/historico/application", 359, 20)
                    .pacoteDeInterfaces("historico-service", "br/com/fiap/hospital/historico/infrastructure/persistence/repository",
                            "EventoProcessadoJpaRepository", "ConsultaHistoricoJpaRepository", "ConsultaEventoJpaRepository");
            VerificadorDeCobertura.Resultado resultado = verificar(workspace);
            assertThat(resultado.violacoes()).isEmpty();
            assertThat(resultado.tabela().get(1)).contains("1819", "51", "97.27%");
            assertThat(resultado.tabela().get(2)).contains("309", "4", "98.72%");
            assertThat(resultado.tabela().get(3)).contains("158", "0", "100.00%");
        }

        @Test
        @DisplayName("INSTRUCTION presente e LINE ausente é recusado")
        void instructionSemLineERecusado() {
            String pacote = "<package name=\"" + MODEL + "\"><class name=\"" + MODEL + "/Classe\" sourcefilename=\"Classe.java\"/>"
                    + "<sourcefile name=\"Classe.java\"/><counter type=\"INSTRUCTION\" missed=\"1\" covered=\"9\"/></package>";
            assertThat(verificarRelatorio(trocarPacote(base().xml(), MODEL, pacote)).violacoes()).containsExactly(AUSENTE_MODEL);
        }

        @Test
        @DisplayName("<method> sem counter e LINE direto ausente é recusado")
        void metodoSemLineERecusado() {
            String pacote = "<package name=\"" + MODEL + "\"><class name=\"" + MODEL + "/Classe\" sourcefilename=\"Classe.java\">"
                    + "<method name=\"executar\" desc=\"()V\" line=\"3\"/></class><sourcefile name=\"Classe.java\"/></package>";
            assertThat(verificarRelatorio(trocarPacote(base().xml(), MODEL, pacote)).violacoes()).containsExactly(AUSENTE_MODEL);
        }

        @Test
        @DisplayName("<line> no sourcefile e LINE direto ausente é recusado")
        void linhaSemLineERecusada() {
            String pacote = "<package name=\"" + MODEL + "\"><class name=\"" + MODEL + "/Classe\" sourcefilename=\"Classe.java\"/>"
                    + "<sourcefile name=\"Classe.java\"><line nr=\"3\" mi=\"1\" ci=\"0\" mb=\"0\" cb=\"0\"/></sourcefile></package>";
            assertThat(verificarRelatorio(trocarPacote(base().xml(), MODEL, pacote)).violacoes()).containsExactly(AUSENTE_MODEL);
        }

        @Test
        @DisplayName("counter LINE só descendente, sem LINE direto, é recusado")
        void lineSoDescendenteERecusado() {
            String pacote = "<package name=\"" + MODEL + "\"><class name=\"" + MODEL + "/Classe\" sourcefilename=\"Classe.java\">"
                    + "<counter type=\"LINE\" missed=\"5\" covered=\"45\"/></class><sourcefile name=\"Classe.java\"/></package>";
            assertThat(verificarRelatorio(trocarPacote(base().xml(), MODEL, pacote)).violacoes()).containsExactly(AUSENTE_MODEL);
        }

        @Test
        @DisplayName("pacote fora de domain e application com LINE ausente é recusado")
        void pacoteForaDosEscoposSemLineERecusado() {
            String nome = "br/com/fiap/hospital/notificacao";
            String pacote = "<package name=\"" + nome + "\"><class name=\"" + nome + "/Classe\" sourcefilename=\"Classe.java\"/>"
                    + "<sourcefile name=\"Classe.java\"/><counter type=\"METHOD\" missed=\"0\" covered=\"4\"/></package>";
            assertThat(verificarRelatorio(trocarPacote(base().xml(), nome, pacote)).violacoes())
                    .containsExactly("contador LINE ausente no pacote " + nome + ", que tem evidencia executavel");
        }

        @Test
        @DisplayName("package vazio é recusado")
        void pacoteVazioERecusado() {
            String nome = "br/com/fiap/hospital/historico/vazio";
            String xml = base().xml().replace("<group name=\"historico-service\">",
                    "<group name=\"historico-service\"><package name=\"" + nome + "\"/>");
            assertThat(verificarRelatorio(xml).violacoes())
                    .containsExactly("pacote " + nome + " vazio ou ambiguo: sem class ou sem sourcefile");
        }

        @Test
        @DisplayName("package com class e sem sourcefile é ambíguo e recusado")
        void pacoteSemSourcefileERecusado() {
            String nome = "br/com/fiap/hospital/historico/ambiguo";
            String xml = base().xml().replace("<group name=\"historico-service\">",
                    "<group name=\"historico-service\"><package name=\"" + nome + "\"><class name=\"" + nome + "/Porta\" sourcefilename=\"Porta.java\"/></package>");
            assertThat(verificarRelatorio(xml).violacoes())
                    .containsExactly("pacote " + nome + " vazio ou ambiguo: sem class ou sem sourcefile");
        }

        @Test
        @DisplayName("package duplicado no group é recusado")
        void pacoteDuplicadoERecusado() {
            String xml = base().xml();
            String pacote = bloco(xml, "<package name=\"br/com/fiap/hospital/historico\">", "</package>");
            assertThat(verificarRelatorio(xml.replace(pacote, pacote + pacote)).violacoes())
                    .containsExactly("pacote duplicado no group historico-service: br/com/fiap/hospital/historico");
        }

        @Test
        @DisplayName("soma dos packages divergente do LINE do group é recusada")
        void somaDosPacotesDivergenteDoGrupo() {
            String xml = trocarLineDoGrupo(base().xml(), "historico-service", "<counter type=\"LINE\" missed=\"19\" covered=\"181\"/>");
            String total = WorkspaceSintetico.linha(BigInteger.valueOf(842), BigInteger.valueOf(108));
            xml = xml.replace(total + "</report>", WorkspaceSintetico.linha(BigInteger.valueOf(843), BigInteger.valueOf(107)) + "</report>");
            assertThat(verificarRelatorio(xml).violacoes()).containsExactly(
                    "soma LINE dos packages do group historico-service (cobertas=180, perdidas=20) diverge do LINE do group (cobertas=181, perdidas=19)");
        }

        @Test
        @DisplayName("pacote 0/0 ainda precisa fechar com o LINE do group")
        void pacoteZeroNaoEscondeDivergencia() {
            WorkspaceSintetico workspace = base().pacoteDeInterfaces("historico-service", "br/com/fiap/hospital/historico/repository", "Repo");
            String xml = trocarLineDoGrupo(workspace.xml(), "historico-service", "<counter type=\"LINE\" missed=\"20\" covered=\"181\"/>");
            xml = xml.replace(WorkspaceSintetico.linha(BigInteger.valueOf(842), BigInteger.valueOf(108)) + "</report>",
                    WorkspaceSintetico.linha(BigInteger.valueOf(843), BigInteger.valueOf(108)) + "</report>");
            assertThat(verificarRelatorio(xml).violacoes()).containsExactly(
                    "soma LINE dos packages do group historico-service (cobertas=180, perdidas=20) diverge do LINE do group (cobertas=181, perdidas=20)");
        }

        @Test
        @DisplayName("subárvore só com pacotes 0/0 não tem total positivo e é recusada")
        void subarvoreSoDeInterfacesERecusada() {
            WorkspaceSintetico workspace = base();
            workspace.grupos.get("agendamento-service").removeIf(p -> p.nome().startsWith(APPLICATION));
            workspace.pacoteDeInterfaces("agendamento-service", APPLICATION + "/port", "CasoDeUso");
            assertThat(verificar(workspace).violacoes()).containsExactly("escopo " + APPLICATION + " sem linhas");
        }

        @Test
        @DisplayName("LINE ausente e duplicado no group e no report continuam recusados com pacotes 0/0 presentes")
        void negativosDeGroupEReportPreservados() {
            WorkspaceSintetico workspace = base().pacoteDeInterfaces("notificacao-service", "br/com/fiap/hospital/notificacao/repository", "Repo");
            String xml = workspace.xml();
            assertThat(verificarRelatorio(semLineDoGrupo(xml, "notificacao-service")).violacoes())
                    .containsExactly("contador LINE ausente no group notificacao-service");
            String duplicado = trocarLineDoGrupo(xml, "notificacao-service",
                    "<counter type=\"LINE\" missed=\"30\" covered=\"170\"/><counter type=\"LINE\" missed=\"30\" covered=\"170\"/>");
            assertThat(verificarRelatorio(duplicado).violacoes())
                    .containsExactly("contador LINE duplicado no group notificacao-service");
            String total = WorkspaceSintetico.linha(BigInteger.valueOf(842), BigInteger.valueOf(108));
            assertThat(verificarRelatorio(xml.replace(total + "</report>", "</report>")).violacoes())
                    .containsExactly("contador LINE ausente no report");
            assertThat(verificarRelatorio(xml.replace(total + "</report>", total + total + "</report>")).violacoes())
                    .containsExactly("contador LINE duplicado no report");
        }
    }

    @Nested
    @DisplayName("Scenario: Evidência de outra sessão é recusada")
    class Sessao {

        @Test
        @DisplayName("marcador ausente")
        void marcadorAusente() throws IOException {
            Path relatorio = base().gravar();
            Path marcador = raiz.resolve(VerificadorDeCobertura.MARCADOR);
            Files.delete(marcador);
            assertThat(VerificadorDeCobertura.verificar(relatorio, raiz, SESSAO).violacoes())
                    .containsExactly("marcador de sessao ausente: " + marcador);
        }

        @Test
        @DisplayName("marcador de outra sessão, um milissegundo depois")
        void marcadorDeOutraSessao() {
            Path relatorio = base().gravar();
            assertThat(VerificadorDeCobertura.verificar(relatorio, raiz, "2026-09-12T10:15:30.124Z").violacoes())
                    .containsExactly("marcador de outra sessao: esperado [2026-09-12T10:15:30.124Z], gravado [" + SESSAO + "]");
        }

        @Test
        @DisplayName("igualdade textual: quebra de linha final não é a mesma sessão")
        void igualdadeTextual() {
            Path relatorio = base().gravar();
            WorkspaceSintetico.escrever(raiz.resolve(VerificadorDeCobertura.MARCADOR), SESSAO + "\n");
            assertThat(VerificadorDeCobertura.verificar(relatorio, raiz, SESSAO).violacoes())
                    .containsExactly("marcador de outra sessao: esperado [" + SESSAO + "], gravado [" + SESSAO + "\n]");
        }

        @Test
        @DisplayName("identificador não resolvido pelo Maven ou em branco")
        void identificadorNaoInformado() {
            Path relatorio = base().gravar();
            assertThat(VerificadorDeCobertura.verificar(relatorio, raiz, "${sessao.verificacao}").violacoes())
                    .containsExactly("identificador de sessao nao informado: ${sessao.verificacao}");
            assertThat(VerificadorDeCobertura.verificar(relatorio, raiz, " ").violacoes())
                    .containsExactly("identificador de sessao nao informado:  ");
        }

        @Test
        @DisplayName("evidência recente de outra sessão continua recusada: o relógio não decide")
        void evidenciaRecenteDeOutraSessao() throws IOException {
            Path relatorio = base().gravar();
            WorkspaceSintetico.escrever(raiz.resolve(VerificadorDeCobertura.MARCADOR), "2026-09-11T23:59:59.999Z");
            tocar(Instant.now().plus(1, ChronoUnit.HOURS));
            assertThat(VerificadorDeCobertura.verificar(relatorio, raiz, SESSAO).violacoes()).containsExactly(
                    "marcador de outra sessao: esperado [" + SESSAO + "], gravado [2026-09-11T23:59:59.999Z]");
        }
    }

    @Nested
    @DisplayName("Scenario: Aceitação não depende da resolução do relógio do sistema de arquivos")
    class Relogio {

        @Test
        @DisplayName("datas de modificação arredondadas dois segundos antes do início da sessão são aceitas")
        void datasArredondadasParaAntesSaoAceitas() throws IOException {
            Path relatorio = base().gravar();
            Instant inicio = Instant.parse(SESSAO);
            tocar(inicio.truncatedTo(ChronoUnit.SECONDS).minusSeconds(2));
            assertThat(Files.getLastModifiedTime(relatorio).toInstant()).isBefore(inicio);
            assertThat(VerificadorDeCobertura.verificar(relatorio, raiz, SESSAO).violacoes()).isEmpty();
        }
    }

    private void tocar(Instant instante) throws IOException {
        try (Stream<Path> arquivos = Files.walk(raiz)) {
            for (Path arquivo : arquivos.filter(Files::isRegularFile).toList()) {
                Files.setLastModifiedTime(arquivo, FileTime.from(instante));
            }
        }
    }

    @Nested
    @DisplayName("parser seguro e offline")
    class ParserSeguro {

        @Test
        @DisplayName("a DTD pública do JaCoCo não é buscada")
        void dtdPublicaNaoEBuscada() {
            WorkspaceSintetico workspace = base();
            assertThat(workspace.xml()).contains("<!DOCTYPE report PUBLIC");
            assertThat(verificar(workspace).violacoes()).isEmpty();
        }

        @Test
        @DisplayName("DTD externa nunca é lida: uma DTD malformada não interfere")
        void dtdExternaNuncaELida(@TempDir Path fora) throws IOException {
            Path dtd = fora.resolve("report.dtd");
            Files.writeString(dtd, "<!ELEMENT isto nao e uma DTD");
            String xml = base().xml().replace(
                    "<!DOCTYPE report PUBLIC \"-//JACOCO//DTD Report 1.1//EN\" \"report.dtd\">",
                    "<!DOCTYPE report SYSTEM \"" + dtd.toUri() + "\">");
            assertThat(xml).contains("SYSTEM");
            assertThat(verificarRelatorio(xml).violacoes()).isEmpty();
        }

        @Test
        @DisplayName("entidade definida só na DTD externa é recusada, e não resolvida")
        void entidadeDaDtdExternaERecusada(@TempDir Path fora) throws IOException {
            Path dtd = fora.resolve("report.dtd");
            Files.writeString(dtd, "<!ENTITY grupo \"shared-contracts\">");
            String xml = "<?xml version=\"1.0\"?><!DOCTYPE report SYSTEM \"" + dtd.toUri() + "\">"
                    + "<report name=\"x\"><group name=\"a\">&grupo;</group></report>";
            assertThat(verificarRelatorio(xml).violacoes())
                    .containsExactly("relatorio de cobertura recusado: documento referencia entidade: recusado");
        }

        @Test
        @DisplayName("entidade interna declarada é recusada")
        void entidadeInternaERecusada() {
            String xml = "<?xml version=\"1.0\"?><!DOCTYPE report [<!ENTITY total \"842\">]>"
                    + "<report name=\"x\"><group name=\"a\">&total;</group></report>";
            List<String> violacoes = verificarRelatorio(xml).violacoes();
            assertThat(violacoes).hasSize(1);
            assertThat(violacoes.getFirst()).startsWith("relatorio de cobertura recusado: documento declara entidade");
        }

        @Test
        @DisplayName("entidade externa é recusada sem que o arquivo seja lido")
        void entidadeExternaERecusada(@TempDir Path fora) throws IOException {
            Path segredo = fora.resolve("segredo.txt");
            Files.writeString(segredo, "SEGREDO-M10");
            String xml = "<?xml version=\"1.0\"?><!DOCTYPE report [<!ENTITY s SYSTEM \"" + segredo.toUri() + "\">]>"
                    + "<report name=\"x\"><group name=\"a\">&s;</group></report>";
            List<String> violacoes = verificarRelatorio(xml).violacoes();
            assertThat(violacoes).hasSize(1);
            assertThat(violacoes.getFirst()).startsWith("relatorio de cobertura recusado:").doesNotContain("SEGREDO");
        }
    }

    @Nested
    @DisplayName("invocação pelo exec do build")
    class Invocacao {

        private int executar(ByteArrayOutputStream saida, String... args) {
            return VerificadorDeCobertura.executar(args, new PrintStream(saida, true, StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("aprovado sai com zero e imprime a tabela de escopos")
        void aprovadoSaiComZero() {
            Path relatorio = base().gravar();
            ByteArrayOutputStream saida = new ByteArrayOutputStream();
            assertThat(executar(saida, relatorio.toString(), raiz.toString(), SESSAO)).isZero();
            assertThat(saida.toString(StandardCharsets.UTF_8))
                    .contains("global", "domain", "application", "88.63%", "Gate de cobertura: APROVADO");
        }

        @Test
        @DisplayName("reprovado sai com um e imprime as violações")
        void reprovadoSaiComUm() {
            Path relatorio = base().gravar();
            ByteArrayOutputStream saida = new ByteArrayOutputStream();
            assertThat(executar(saida, relatorio.toString(), raiz.toString(), "outra")).isEqualTo(1);
            assertThat(saida.toString(StandardCharsets.UTF_8))
                    .contains("Gate de cobertura: REPROVADO", "marcador de outra sessao");
        }

        @Test
        @DisplayName("argumentos errados saem com dois")
        void argumentosErradosSaemComDois() {
            assertThat(executar(new ByteArrayOutputStream(), "so-um")).isEqualTo(2);
        }
    }
}
