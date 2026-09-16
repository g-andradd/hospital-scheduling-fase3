package br.com.fiap.hospital.qualidade;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Logs estruturados so no profile {@code docker} dos tres servicos (D6), verificados sobre os
 * arquivos de configuracao reais, com leitura linha a linha e so JDK.
 *
 * <p>Os negativos partem do conteudo real de cada servico e mudam uma unica coisa: assim cada um
 * prova que a regra reprova exatamente a mutacao aplicada.
 */
@DisplayName("Logs estruturados no profile docker")
class LogsEstruturadosTest {

    private static final List<String> SERVICOS = List.of("agendamento-service", "notificacao-service", "historico-service");
    private static final String PERFIL_DOCKER = "application-docker.yml";
    private static final String FORMATO = "logging.structured.format.console";
    private static final String SERVICO = "logging.structured.json.add.service";
    private static final String PREFIXO = "logging.structured";

    private static final Pattern CHAVE = Pattern.compile("^([A-Za-z0-9_.\\-]+):(?:\\s+(.*))?$");

    /** Chave YAML lida, com o caminho completo, o valor escalar e a linha de origem. */
    private record Entrada(String caminho, String valor, int linha) {}

    /** Arquivos {@code application*.yml} de um servico, pelo nome do arquivo. */
    private static Map<String, String> arquivosReais(String servico) {
        Path recursos = PomsDoReactor.RAIZ.resolve(servico).resolve("src/main/resources");
        Map<String, String> arquivos = new LinkedHashMap<>();
        try (Stream<Path> lista = Files.list(recursos)) {
            for (Path arquivo : lista.filter(p -> p.getFileName().toString().matches("application.*\\.ya?ml")).sorted().toList()) {
                arquivos.put(arquivo.getFileName().toString(), Files.readString(arquivo, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return arquivos;
    }

    static List<String> verificar(String servico, Map<String, String> arquivos) {
        List<String> violacoes = new ArrayList<>();
        String docker = arquivos.get(PERFIL_DOCKER);
        List<Entrada> doDocker = docker == null ? List.of() : entradas(docker);
        if (doDocker.stream().noneMatch(e -> e.caminho().equals(FORMATO) && e.valor().equals("logstash"))) {
            violacoes.add(servico + ": " + PERFIL_DOCKER + " sem " + FORMATO + ": logstash");
        }
        if (doDocker.stream().noneMatch(e -> e.caminho().equals(SERVICO) && e.valor().equals(servico))) {
            violacoes.add(servico + ": " + PERFIL_DOCKER + " sem " + SERVICO + ": " + servico);
        }
        arquivos.forEach((nome, conteudo) -> {
            if (nome.equals(PERFIL_DOCKER)) return;
            for (Entrada entrada : entradas(conteudo)) {
                if (entrada.caminho().equals(PREFIXO) || entrada.caminho().startsWith(PREFIXO + ".")) {
                    violacoes.add(servico + ": " + entrada.caminho() + " fora do profile docker, em " + nome
                            + " linha " + entrada.linha());
                }
            }
        });
        return violacoes;
    }

    /** Leitura linha a linha: cada documento YAML reinicia o caminho; comentarios nao contam. */
    private static List<Entrada> entradas(String yaml) {
        List<Entrada> entradas = new ArrayList<>();
        Deque<Map.Entry<Integer, String>> pilha = new ArrayDeque<>();
        String[] linhas = yaml.replace("\r", "").split("\n", -1);
        for (int indice = 0; indice < linhas.length; indice++) {
            String bruta = linhas[indice];
            String conteudo = bruta.strip();
            if (conteudo.equals("---")) {
                pilha.clear();
                continue;
            }
            if (conteudo.isEmpty() || conteudo.startsWith("#")) continue;
            int recuo = bruta.length() - bruta.stripLeading().length();
            Matcher chave = CHAVE.matcher(semComentario(conteudo));
            if (!chave.matches()) continue;
            while (!pilha.isEmpty() && pilha.peek().getKey() >= recuo) pilha.pop();
            List<String> partes = new ArrayList<>(pilha.stream().map(Map.Entry::getValue).toList());
            java.util.Collections.reverse(partes);
            partes.add(chave.group(1));
            String caminho = String.join(".", partes);
            String valor = chave.group(2) == null ? "" : chave.group(2).strip();
            entradas.add(new Entrada(caminho, valor.replaceAll("^[\"']|[\"']$", ""), indice + 1));
            if (valor.isEmpty()) pilha.push(Map.entry(recuo, chave.group(1)));
        }
        return entradas;
    }

    private static String semComentario(String conteudo) {
        int comentario = conteudo.indexOf(" #");
        return (comentario < 0 ? conteudo : conteudo.substring(0, comentario)).strip();
    }

    @Test
    @DisplayName("os arquivos reais dos três serviços atendem")
    void arquivosReaisAtendem() {
        for (String servico : SERVICOS) {
            Map<String, String> arquivos = arquivosReais(servico);
            assertThat(arquivos).as(servico).containsKeys("application.yml", PERFIL_DOCKER);
            assertThat(verificar(servico, arquivos)).as(servico).isEmpty();
        }
    }

    @Test
    @DisplayName("Scenario: Profile padrão continua em texto")
    void profilePadraoContinuaEmTexto() {
        for (String servico : SERVICOS) {
            arquivosReais(servico).forEach((nome, conteudo) -> {
                if (nome.equals(PERFIL_DOCKER)) return;
                assertThat(entradas(conteudo)).as("%s %s", servico, nome)
                        .noneMatch(e -> e.caminho().startsWith(PREFIXO));
            });
        }
    }

    static Stream<Arguments> negativos() {
        List<Arguments> casos = new ArrayList<>();
        for (String servico : SERVICOS) {
            casos.add(Arguments.of(servico, "formato ausente no profile docker",
                    (java.util.function.UnaryOperator<Map<String, String>>) arquivos -> {
                        arquivos.put(PERFIL_DOCKER, arquivos.get(PERFIL_DOCKER).replace("      console: logstash\n", ""));
                        return arquivos;
                    },
                    List.of(servico + ": " + PERFIL_DOCKER + " sem " + FORMATO + ": logstash")));
            casos.add(Arguments.of(servico, "arquivo do profile docker ausente",
                    (java.util.function.UnaryOperator<Map<String, String>>) arquivos -> {
                        arquivos.remove(PERFIL_DOCKER);
                        return arquivos;
                    },
                    List.of(servico + ": " + PERFIL_DOCKER + " sem " + FORMATO + ": logstash",
                            servico + ": " + PERFIL_DOCKER + " sem " + SERVICO + ": " + servico)));
        }
        String servico = "notificacao-service";
        casos.add(Arguments.of(servico, "identificação do serviço com outro nome",
                (java.util.function.UnaryOperator<Map<String, String>>) arquivos -> {
                    arquivos.put(PERFIL_DOCKER, arquivos.get(PERFIL_DOCKER).replace("service: notificacao-service", "service: outro"));
                    return arquivos;
                },
                List.of(servico + ": " + PERFIL_DOCKER + " sem " + SERVICO + ": " + servico)));
        casos.add(Arguments.of("agendamento-service", "chave no application.yml",
                (java.util.function.UnaryOperator<Map<String, String>>) arquivos -> {
                    arquivos.put("application.yml", "logging:\n  structured:\n    format:\n      console: logstash\n"
                            + arquivos.get("application.yml"));
                    return arquivos;
                },
                List.of("agendamento-service: logging.structured fora do profile docker, em application.yml linha 2",
                        "agendamento-service: logging.structured.format fora do profile docker, em application.yml linha 3",
                        "agendamento-service: logging.structured.format.console fora do profile docker, em application.yml linha 4")));
        casos.add(Arguments.of("agendamento-service", "chave em outro arquivo de profile",
                (java.util.function.UnaryOperator<Map<String, String>>) arquivos -> {
                    arquivos.put("application-demo.yml", arquivos.get("application-demo.yml")
                            + "\nlogging.structured.format.console: ecs\n");
                    return arquivos;
                },
                List.of("agendamento-service: logging.structured.format.console fora do profile docker, em application-demo.yml linha "
                        + (arquivosReais("agendamento-service").get("application-demo.yml").replace("\r", "").split("\n", -1).length + 1))));
        casos.add(Arguments.of("historico-service", "chave em outro profile dentro do application.yml",
                (java.util.function.UnaryOperator<Map<String, String>>) arquivos -> {
                    arquivos.put("application.yml", arquivos.get("application.yml")
                            + "\n---\nspring:\n  config:\n    activate:\n      on-profile: dev\nlogging:\n  structured:\n    format:\n      console: logstash\n");
                    return arquivos;
                },
                List.of("historico-service: logging.structured fora do profile docker, em application.yml linha "
                                + (linhas("historico-service", "application.yml") + 7),
                        "historico-service: logging.structured.format fora do profile docker, em application.yml linha "
                                + (linhas("historico-service", "application.yml") + 8),
                        "historico-service: logging.structured.format.console fora do profile docker, em application.yml linha "
                                + (linhas("historico-service", "application.yml") + 9))));
        return casos.stream();
    }

    private static int linhas(String servico, String arquivo) {
        return arquivosReais(servico).get(arquivo).replace("\r", "").split("\n", -1).length;
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("negativos")
    @DisplayName("Scenario: Profile docker sem formato estruturado é detectado")
    void negativo(String servico, String caso, java.util.function.UnaryOperator<Map<String, String>> mutacao,
                  List<String> esperadas) {
        Map<String, String> arquivos = mutacao.apply(new LinkedHashMap<>(arquivosReais(servico)));

        assertThat(verificar(servico, arquivos)).containsExactlyElementsOf(esperadas);
    }
}
