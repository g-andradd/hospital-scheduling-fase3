package br.com.fiap.hospital.qualidade;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Um reactor sintetico com o formato do real: POM raiz com os seis modulos, marcador de
 * sessao, {@code jacoco.exec} por modulo de codigo e o relatorio agregado do JaCoCo.
 *
 * <p>O relatorio e montado a partir dos pacotes, e os contadores do group e do report saem da
 * soma — como no JaCoCo. Cada negativo muda so o que quer provar, e o resto continua coerente.
 */
final class WorkspaceSintetico {

    static final String SESSAO = "2026-09-12T10:15:30.123Z";
    static final List<String> MODULOS = List.of(
            "shared-contracts", "shared-security", "agendamento-service", "notificacao-service", "historico-service");
    static final String DOMAIN = VerificadorDeCobertura.PREFIXO_DOMAIN;
    static final String APPLICATION = VerificadorDeCobertura.PREFIXO_APPLICATION;

    /** Pacote do relatorio; {@code soInterfaces} gera o formato do JaCoCo para pacote sem linhas executaveis. */
    record Pacote(String nome, BigInteger cobertas, BigInteger perdidas, boolean soInterfaces) {

        Pacote(String nome, BigInteger cobertas, BigInteger perdidas) {
            this(nome, cobertas, perdidas, false);
        }
    }

    final Path raiz;
    final Map<String, List<Pacote>> grupos = new LinkedHashMap<>();
    /** Contador LINE do report escrito no lugar da soma, quando nao nulo. */
    String totalForcado;
    boolean comDoctype = true;

    WorkspaceSintetico(Path raiz) {
        this.raiz = raiz;
        MODULOS.forEach(m -> grupos.put(m, new ArrayList<>()));
    }

    /** Dados acima dos pisos: global 88,63%, domain 93,33%, application 92,00%. */
    static WorkspaceSintetico acimaDosPisos(Path raiz) {
        return new WorkspaceSintetico(raiz)
                .pacote("shared-contracts", "br/com/fiap/hospital/contracts", 90, 10)
                .pacote("shared-security", "br/com/fiap/hospital/security", 90, 10)
                .pacote("agendamento-service", DOMAIN, 95, 5)
                .pacote("agendamento-service", DOMAIN + "/model", 45, 5)
                .pacote("agendamento-service", APPLICATION, 92, 8)
                .pacote("agendamento-service", "br/com/fiap/hospital/agendamento/infrastructure", 80, 20)
                .pacote("notificacao-service", "br/com/fiap/hospital/notificacao", 170, 30)
                .pacote("historico-service", "br/com/fiap/hospital/historico", 180, 20);
    }

    WorkspaceSintetico pacote(String grupo, String nome, long cobertas, long perdidas) {
        return pacote(grupo, nome, BigInteger.valueOf(cobertas), BigInteger.valueOf(perdidas));
    }

    WorkspaceSintetico pacote(String grupo, String nome, BigInteger cobertas, BigInteger perdidas) {
        grupos.computeIfAbsent(grupo, g -> new ArrayList<>()).add(new Pacote(nome, cobertas, perdidas));
        return this;
    }

    /** Pacote so de interfaces: class e sourcefile vazios, nenhum counter, method ou line. */
    WorkspaceSintetico pacoteDeInterfaces(String grupo, String nome, String... interfaces) {
        grupos.computeIfAbsent(grupo, g -> new ArrayList<>())
                .add(new Pacote(nome, BigInteger.ZERO, BigInteger.ZERO, true));
        interfacesPorPacote.put(nome, List.of(interfaces));
        return this;
    }

    private final Map<String, List<String>> interfacesPorPacote = new LinkedHashMap<>();

    WorkspaceSintetico semPacotes(String grupo) {
        grupos.get(grupo).clear();
        return this;
    }

    Path relatorio() {
        return raiz.resolve("quality-gates/target/site/jacoco-aggregate/jacoco.xml");
    }

    /** Grava todo o workspace e devolve o caminho do relatorio. */
    Path gravar() {
        return gravar(xml());
    }

    /** Grava o workspace com o conteudo dado no lugar do relatorio montado. */
    Path gravar(String conteudoDoRelatorio) {
        escrever(raiz.resolve("pom.xml"), pomRaiz());
        escrever(raiz.resolve(VerificadorDeCobertura.MARCADOR), SESSAO);
        for (String modulo : MODULOS) {
            escrever(raiz.resolve(modulo).resolve("target").resolve("jacoco.exec"), "dados de execucao");
        }
        escrever(relatorio().resolveSibling("index.html"), "<html>agregado</html>");
        escrever(relatorio(), conteudoDoRelatorio);
        return relatorio();
    }

    static void escrever(Path arquivo, String conteudo) {
        try {
            Files.createDirectories(arquivo.getParent());
            Files.writeString(arquivo, conteudo, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String pomRaiz() {
        StringBuilder pom = new StringBuilder("<project><groupId>br.com.fiap.hospital</groupId><modules>");
        MODULOS.forEach(m -> pom.append("<module>").append(m).append("</module>"));
        return pom.append("<module>quality-gates</module></modules></project>").toString();
    }

    static String linha(BigInteger cobertas, BigInteger perdidas) {
        return "<counter type=\"INSTRUCTION\" missed=\"1\" covered=\"1\"/>"
                + "<counter type=\"LINE\" missed=\"" + perdidas + "\" covered=\"" + cobertas + "\"/>";
    }

    /** Pacote no formato do JaCoCo: class com method, sourcefile com line, e os contadores. */
    String pacote(Pacote pacote) {
        StringBuilder xml = new StringBuilder("<package name=\"").append(pacote.nome()).append("\">");
        if (pacote.soInterfaces()) {
            List<String> interfaces = interfacesPorPacote.getOrDefault(pacote.nome(), List.of("Porta"));
            interfaces.forEach(i -> xml.append("<class name=\"").append(pacote.nome()).append('/').append(i)
                    .append("\" sourcefilename=\"").append(i).append(".java\"/>"));
            interfaces.forEach(i -> xml.append("<sourcefile name=\"").append(i).append(".java\"/>"));
            return xml.append("</package>").toString();
        }
        String contadores = linha(pacote.cobertas(), pacote.perdidas());
        xml.append("<class name=\"").append(pacote.nome()).append("/Classe\" sourcefilename=\"Classe.java\">")
                .append("<method name=\"executar\" desc=\"()V\" line=\"1\">").append(contadores).append("</method>")
                .append(contadores).append("</class>")
                .append("<sourcefile name=\"Classe.java\"><line nr=\"1\" mi=\"0\" ci=\"1\" mb=\"0\" cb=\"0\"/>")
                .append(contadores).append("</sourcefile>")
                .append(contadores);
        return xml.append("</package>").toString();
    }

    String xml() {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        if (comDoctype) {
            xml.append("<!DOCTYPE report PUBLIC \"-//JACOCO//DTD Report 1.1//EN\" \"report.dtd\">");
        }
        xml.append("<report name=\"quality-gates\">");
        BigInteger cobertas = BigInteger.ZERO;
        BigInteger perdidas = BigInteger.ZERO;
        for (Map.Entry<String, List<Pacote>> grupo : grupos.entrySet()) {
            BigInteger cobertasDoGrupo = BigInteger.ZERO;
            BigInteger perdidasDoGrupo = BigInteger.ZERO;
            xml.append("<group name=\"").append(grupo.getKey()).append("\">");
            for (Pacote pacote : grupo.getValue()) {
                xml.append(pacote(pacote));
                cobertasDoGrupo = cobertasDoGrupo.add(pacote.cobertas());
                perdidasDoGrupo = perdidasDoGrupo.add(pacote.perdidas());
            }
            xml.append(linha(cobertasDoGrupo, perdidasDoGrupo)).append("</group>");
            cobertas = cobertas.add(cobertasDoGrupo);
            perdidas = perdidas.add(perdidasDoGrupo);
        }
        xml.append(totalForcado != null ? totalForcado : linha(cobertas, perdidas));
        return xml.append("</report>").toString();
    }
}
