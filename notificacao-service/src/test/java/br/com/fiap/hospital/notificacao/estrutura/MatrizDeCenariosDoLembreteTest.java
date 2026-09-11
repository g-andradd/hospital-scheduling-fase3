package br.com.fiap.hospital.notificacao.estrutura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;

/**
 * A matriz Scenario → metodo do M07, mantida em codigo e conferida contra a spec.
 *
 * <p>Uma matriz so em markdown envelhece em silencio: o metodo e renomeado, o Scenario muda
 * de titulo, e a tabela continua bonita. Aqui cada entrada precisa apontar um teste que
 * existe e que carrega o titulo do Scenario, e o conjunto de titulos precisa ser exatamente
 * o dos seis Requirements do M07 — lidos do delta enquanto a change esta ativa, e da
 * capability promovida depois do archive.
 */
@DisplayName("Matriz Scenario -> metodo do lembrete D-1")
class MatrizDeCenariosDoLembreteTest {

    private static final String IT = "br.com.fiap.hospital.notificacao.integracao.";
    private static final String ESTRUTURA = "br.com.fiap.hospital.notificacao.estrutura.";
    private static final String SCHEDULER = "br.com.fiap.hospital.notificacao.scheduler.";

    private static final String JANELA = "Lembrete D-1 para consultas ativas nas próximas 24 horas";
    private static final String REGISTRO = "Lembrete entregue pelo canal configurado e registrado";
    private static final String UNICIDADE = "No máximo um lembrete D-1 por consulta";
    private static final String SELECAO = "Seleção de candidatos em comando único no armazenamento";
    private static final String AUTOMATICA = "Execução automática periódica e configurável";
    private static final String DISPARO = "Disparo manual protegido por perfil";

    record Entrada(String requirement, String scenario, String classe, String metodo) { }

    static final List<Entrada> MATRIZ = List.of(
            new Entrada(JANELA, "Consulta a 23h59 da execução recebe lembrete", IT + "JanelaDoLembreteIT", "consultaA23h59Recebe"),
            new Entrada(JANELA, "Consulta exatamente a 24 horas recebe lembrete", IT + "JanelaDoLembreteIT", "consultaExatamenteA24hRecebe"),
            new Entrada(JANELA, "Consulta a 24h01 não recebe lembrete", IT + "JanelaDoLembreteIT", "consultaA24h01NaoRecebe"),
            new Entrada(JANELA, "Consulta no instante da execução ou no passado não recebe lembrete", IT + "JanelaDoLembreteIT", "consultaNoInstanteOuNoPassadoNaoRecebe"),
            new Entrada(JANELA, "Consultas agendadas e confirmadas são lembradas", IT + "JanelaDoLembreteIT", "agendadasEConfirmadasSaoLembradas"),
            new Entrada(JANELA, "Consultas canceladas e realizadas nunca são lembradas", IT + "JanelaDoLembreteIT", "canceladasERealizadasNuncaSaoLembradas"),
            new Entrada(JANELA, "Execução sem candidatos termina sem efeito", IT + "JanelaDoLembreteIT", "execucaoSemCandidatosTerminaSemEfeito"),
            new Entrada(REGISTRO, "Lembrete confirmado fica registrado como foi entregue", IT + "RegistroDoLembreteIT", "lembreteConfirmadoFicaRegistradoComoFoiEntregue"),
            new Entrada(REGISTRO, "Conteúdo informa médico e horário local da consulta", IT + "RegistroDoLembreteIT", "conteudoInformaMedicoEHorarioLocal"),
            new Entrada(REGISTRO, "Tipo do lembrete fica fora do contrato de eventos", IT + "RegistroDoLembreteIT", "tipoDoLembreteFicaForaDoContratoDeEventos"),
            new Entrada(UNICIDADE, "Execução repetida não lembra de novo", IT + "IdempotenciaDoLembreteIT", "execucaoRepetidaNaoLembraDeNovo"),
            new Entrada(UNICIDADE, "Execuções concorrentes confirmam e entregam um único lembrete", IT + "IdempotenciaDoLembreteIT", "execucoesConcorrentesConfirmamEEntregamUmUnicoLembrete"),
            new Entrada(UNICIDADE, "Consulta remarcada depois do lembrete não recebe outro", IT + "IdempotenciaDoLembreteIT", "consultaRemarcadaDepoisDoLembreteNaoRecebeOutro"),
            new Entrada(UNICIDADE, "Armazenamento recusa segundo lembrete da mesma consulta", IT + "IdempotenciaDoLembreteIT", "armazenamentoRecusaSegundoLembreteDaMesmaConsulta"),
            new Entrada(UNICIDADE, "Falha no envio não deixa lembrete e a consulta segue elegível", IT + "IdempotenciaDoLembreteIT", "falhaNoEnvioNaoDeixaLembreteEConsultaSegueElegivel"),
            new Entrada(UNICIDADE, "Falha em uma consulta não impede as demais", IT + "IdempotenciaDoLembreteIT", "falhaEmUmaConsultaNaoImpedeAsDemais"),
            new Entrada(SELECAO, "Recortes aplicados no comando enviado ao armazenamento", IT + "ConsultaDeCandidatosIT", "recortesAplicadosNoComandoEnviado"),
            new Entrada(SELECAO, "Mais candidatos não multiplicam comandos de leitura", IT + "ConsultaDeCandidatosIT", "maisCandidatosNaoMultiplicamLeituras"),
            new Entrada(AUTOMATICA, "Sem configuração, a varredura automática é horária", SCHEDULER + "AgendadorDeLembretesTest", "semConfiguracaoAVarreduraEHoraria"),
            new Entrada(AUTOMATICA, "Expressão configurada substitui a padrão", SCHEDULER + "AgendadorDeLembretesTest", "expressaoConfiguradaSubstituiAPadrao"),
            new Entrada(AUTOMATICA, "Profile de teste não executa automaticamente", IT + "AgendadorDesligadoIT", "profileDeTesteNaoExecutaAutomaticamente"),
            new Entrada(AUTOMATICA, "Execução automática e disparo manual aplicam a mesma regra", ESTRUTURA + "DelegacaoDoLembreteTest", "automaticoEManualAplicamAMesmaRegra"),
            new Entrada(DISPARO, "Médico dispara a execução", IT + "MatrizDeAutorizacaoNotificacaoIT", "medicoDisparaAExecucao"),
            new Entrada(DISPARO, "Enfermeiro dispara a execução", IT + "MatrizDeAutorizacaoNotificacaoIT", "enfermeiroDisparaAExecucao"),
            new Entrada(DISPARO, "Disparo sem candidatos responde zero", IT + "DisparoManualIT", "disparoSemCandidatosRespondeZero"),
            new Entrada(DISPARO, "Paciente é recusado sem executar a varredura", IT + "MatrizDeAutorizacaoNotificacaoIT", "pacienteERecusadoSemExecutarAVarredura"),
            new Entrada(DISPARO, "Sem token recebe 401", IT + "DisparoManualIT", "semTokenRecebe401"),
            new Entrada(DISPARO, "Token expirado recebe 401", IT + "DisparoManualIT", "tokenExpiradoRecebe401"),
            new Entrada(DISPARO, "Token inválido recebe 401", IT + "DisparoManualIT", "tokenInvalidoRecebe401"),
            new Entrada(DISPARO, "Falha na leitura inicial dos candidatos responde 500 em Problem Detail", IT + "DisparoManualIT", "falhaNaLeituraInicialRespondeProblemDetail500"));

    @Test
    @DisplayName("a matriz cobre exatamente os Scenarios dos seis Requirements do M07")
    void matrizCobreOsScenariosDoM07() throws IOException {
        Map<String, List<String>> spec = scenariosDaSpec();

        assertThat(MATRIZ).as("uma matriz vazia passaria verde sem rastrear nada").isNotEmpty();
        assertThat(spec.keySet())
                .as("os seis Requirements do M07 precisam ser encontrados na spec")
                .containsExactlyInAnyOrderElementsOf(requirementsDa(MATRIZ));
        assertThat(divergencias(MATRIZ, spec)).isEmpty();
        assertThat(MATRIZ).hasSize(30);
    }

    @Test
    @DisplayName("cada entrada aponta um teste que existe e carrega o titulo do Scenario")
    void cadaEntradaApontaUmTesteComOTituloDoScenario() {
        MATRIZ.forEach(MatrizDeCenariosDoLembreteTest::verificarMetodo);
    }

    /** A cobertura acusa o que existe para acusar: linha a menos e titulo divergente. */
    @Test
    @DisplayName("a cobertura acusa linha removida e titulo alterado")
    void coberturaAcusaLinhaRemovidaETituloAlterado() throws IOException {
        Map<String, List<String>> spec = scenariosDaSpec();

        List<Entrada> semUmaLinha = new ArrayList<>(MATRIZ);
        semUmaLinha.remove(4);
        assertThat(divergencias(semUmaLinha, spec)).as("linha removida").isNotEmpty();

        List<Entrada> comTituloAlterado = new ArrayList<>(MATRIZ);
        Entrada original = comTituloAlterado.get(0);
        Entrada alterada = new Entrada(original.requirement(), original.scenario() + " alterado",
                original.classe(), original.metodo());
        comTituloAlterado.set(0, alterada);
        assertThat(divergencias(comTituloAlterado, spec)).as("titulo alterado").isNotEmpty();
        assertThatThrownBy(() -> verificarMetodo(alterada))
                .as("o @DisplayName do metodo deixa de casar com o titulo")
                .isInstanceOf(AssertionError.class);
    }

    static List<String> divergencias(List<Entrada> matriz, Map<String, List<String>> spec) {
        Set<String> daSpec = spec.values().stream().flatMap(List::stream)
                .map(MatrizDeCenariosDoLembreteTest::normalizado)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> daMatriz = matriz.stream().map(e -> normalizado(e.scenario()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> divergencias = new ArrayList<>();
        daSpec.stream().filter(s -> !daMatriz.contains(s)).forEach(s -> divergencias.add("sem metodo: " + s));
        daMatriz.stream().filter(s -> !daSpec.contains(s)).forEach(s -> divergencias.add("fora da spec: " + s));
        return divergencias;
    }

    private static void verificarMetodo(Entrada entrada) {
        Class<?> classe;
        try {
            classe = Class.forName(entrada.classe(), false,
                    MatrizDeCenariosDoLembreteTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new AssertionError("classe inexistente: " + entrada.classe(), e);
        }
        List<Method> metodos = Arrays.stream(classe.getDeclaredMethods())
                .filter(m -> m.getName().equals(entrada.metodo()))
                .toList();
        assertThat(metodos).as("%s#%s", classe.getSimpleName(), entrada.metodo()).hasSize(1);
        Method metodo = metodos.getFirst();
        assertThat(metodo.isAnnotationPresent(Test.class)
                || metodo.isAnnotationPresent(ParameterizedTest.class))
                .as("%s precisa ser teste", entrada.metodo())
                .isTrue();
        DisplayName nome = metodo.getAnnotation(DisplayName.class);
        assertThat(nome).as("%s precisa de @DisplayName", entrada.metodo()).isNotNull();
        assertThat(normalizado(nome.value()))
                .as("%s precisa carregar o titulo do Scenario", entrada.metodo())
                .isEqualTo(normalizado("Scenario: " + entrada.scenario()));
    }

    private static Set<String> requirementsDa(List<Entrada> matriz) {
        return matriz.stream().map(Entrada::requirement)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Scenarios dos Requirements do M07, do delta ativo ou da capability promovida. */
    private static Map<String, List<String>> scenariosDaSpec() throws IOException {
        Path raiz = Files.exists(Path.of("..", "openspec")) ? Path.of("..") : Path.of(".");
        Path delta = raiz.resolve(
                "openspec/changes/add-lembrete-24h/specs/notificacoes-ao-paciente/spec.md");
        Path promovida = raiz.resolve("openspec/specs/notificacoes-ao-paciente/spec.md");
        Path fonte = Files.exists(delta) ? delta : promovida;

        Set<String> doM07 = requirementsDa(MATRIZ);
        Map<String, List<String>> scenarios = new LinkedHashMap<>();
        String atual = null;
        for (String linha : Files.readAllLines(fonte)) {
            if (linha.startsWith("### Requirement: ")) {
                String nome = linha.substring("### Requirement: ".length()).trim();
                atual = doM07.contains(nome) ? nome : null;
                if (atual != null) scenarios.put(atual, new ArrayList<>());
            } else if (atual != null && linha.startsWith("#### Scenario: ")) {
                scenarios.get(atual).add(linha.substring("#### Scenario: ".length()).trim());
            }
        }
        return scenarios;
    }

    /** Sem acentos e sem caixa: os nomes de teste do projeto sao escritos em ASCII. */
    private static String normalizado(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase()
                .replaceAll("\\s+", " ")
                .trim();
    }
}
