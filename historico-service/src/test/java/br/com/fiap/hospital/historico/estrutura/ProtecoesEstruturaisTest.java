package br.com.fiap.hospital.historico.estrutura;

import static org.assertj.core.api.Assertions.*;

import br.com.fiap.hospital.historico.infrastructure.messaging.ConsumidorTransacionalDoHistorico;
import java.io.*;
import java.lang.annotation.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.amqp.rabbit.annotation.*;
import org.springframework.core.annotation.*;
import org.springframework.transaction.annotation.*;

@DisplayName("Proteções estruturais da projeção")
class ProtecoesEstruturaisTest {
    @Test @DisplayName("Scenario: Histórico não declara cliente HTTP")
    void historicoNaoDeclaraClienteHttp() throws IOException {
        Path raiz = Files.exists(Path.of("src/main/java")) ? Path.of("") : Path.of("historico-service");
        String texto = Files.readString(raiz.resolve("pom.xml")) + lerArvore(raiz.resolve("src/main/java")) + lerArvore(raiz.resolve("src/main/resources"));
        assertThat(texto).doesNotContain("RestClient", "RestTemplate", "WebClient", "Feign", "java.net.http.HttpClient",
                "java.net.URI", "java.net.URL", "HttpURLConnection", "java.net.http", "OkHttp", "okhttp3", "org.apache.http", "org.apache.hc", "AGENDAMENTO_URL", "AGENDAMENTO_HOST",
                "AGENDAMENTO_PORT", "localhost:8081", "agendamento-service");
    }

    @Test @DisplayName("a fronteira única de produção é transacional e marca por último")
    void fronteiraUnicaProtegeEfeitoEMarca() throws Exception {
        validar(classesDeProducao());
        Path raiz = Files.exists(Path.of("src/main/java")) ? Path.of("") : Path.of("historico-service");
        String consumidor = Files.readString(raiz.resolve("src/main/java/br/com/fiap/hospital/historico/infrastructure/messaging/ConsumidorTransacionalDoHistorico.java"));
        int indiceEfeito = consumidor.indexOf("projetor.projetar(evento)");
        int indiceMarca = consumidor.indexOf("processados.saveAndFlush");
        assertThat(indiceEfeito).isGreaterThanOrEqualTo(0);
        assertThat(indiceMarca).isGreaterThanOrEqualTo(0);
        assertThat(indiceEfeito).isLessThan(indiceMarca);
    }

    @Test void rejeitaInfratoresDeMetodoClasseMetaERepeticao() {
        for (Class<?> infrator : List.of(InfratorMetodo.class, InfratorClasse.class, InfratorMeta.class, InfratorRepetido.class, InfratorDeInterface.class, InfratorDeSuperclasse.class))
            assertThatThrownBy(() -> validar(List.of(ConsumidorTransacionalDoHistorico.class, infrator))).isInstanceOf(AssertionError.class);
    }

    private void validar(List<Class<?>> classes) {
        List<Fronteira> fronteiras = new ArrayList<>();
        for (Class<?> tipo : classes) {
            if (listener(tipo)) fronteiras.add(new Fronteira(tipo, null));
            for (Method metodo : metodosDaHierarquia(tipo)) if (listener(metodo)) fronteiras.add(new Fronteira(tipo, metodo));
        }
        assertThat(fronteiras).hasSize(1);
        Fronteira fronteira = fronteiras.get(0);
        assertThat(fronteira.tipo()).isEqualTo(ConsumidorTransacionalDoHistorico.class);
        assertThat(fronteira.metodo().getName()).isEqualTo("consumir");
        Transactional tx = MergedAnnotations.from(fronteira.metodo(), MergedAnnotations.SearchStrategy.TYPE_HIERARCHY)
                .get(Transactional.class).synthesize();
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(tx.readOnly()).isFalse();
    }

    private boolean listener(AnnotatedElement elemento) {
        return MergedAnnotations.from(elemento, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY).isPresent(RabbitListener.class);
    }
    private List<Method> metodosDaHierarquia(Class<?> tipo) {
        List<Method> metodos = new ArrayList<>();
        for (Class<?> atual = tipo; atual != null; atual = atual.getSuperclass()) {
            metodos.addAll(Arrays.asList(atual.getDeclaredMethods()));
            for (Class<?> interfaceDaClasse : atual.getInterfaces()) adicionarMetodosDaInterface(interfaceDaClasse, metodos);
        }
        return metodos;
    }
    private void adicionarMetodosDaInterface(Class<?> tipo, List<Method> metodos) {
        metodos.addAll(Arrays.asList(tipo.getDeclaredMethods()));
        for (Class<?> superInterface : tipo.getInterfaces()) adicionarMetodosDaInterface(superInterface, metodos);
    }
    private List<Class<?>> classesDeProducao() throws Exception {
        Path base = Path.of(ConsumidorTransacionalDoHistorico.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (var arquivos = Files.walk(base.resolve("br/com/fiap/hospital/historico"))) {
            return arquivos.filter(p -> p.toString().endsWith(".class")).map(p -> nome(base,p)).map(this::carregar).toList();
        }
    }
    private String nome(Path base, Path arquivo) { String n=base.relativize(arquivo).toString().replace('\\','.').replace('/','.'); return n.substring(0,n.length()-6); }
    private Class<?> carregar(String nome) { try { return Class.forName(nome,false,getClass().getClassLoader()); } catch(ClassNotFoundException e){ throw new IllegalStateException(e); } }
    private String lerArvore(Path raiz) throws IOException { if(!Files.exists(raiz)) return ""; try(var p=Files.walk(raiz)){ return p.filter(Files::isRegularFile).map(this::ler).reduce("",String::concat); } }
    private String ler(Path p) { try { return Files.readString(p); } catch(IOException e){ throw new IllegalStateException(e); } }
    private record Fronteira(Class<?> tipo, Method metodo) { }
    static class InfratorMetodo { @RabbitListener(queues="x") void consumir(){} }
    @ListenerMeta static class InfratorClasse { }
    static class InfratorMeta { @ListenerMeta void consumir(){} }
    static class InfratorRepetido { @RabbitListeners({@RabbitListener(queues="x"),@RabbitListener(queues="y")}) void consumir(){} }
    interface ListenerDeInterface { @RabbitListener(queues="x") void consumir(); }
    static class InfratorDeInterface implements ListenerDeInterface { public void consumir() { } }
    static class SuperclasseComListener { @RabbitListener(queues="x") void consumir() { } }
    static class InfratorDeSuperclasse extends SuperclasseComListener { }
    @Target({ElementType.TYPE,ElementType.METHOD}) @Retention(RetentionPolicy.RUNTIME) @RabbitListener(queues="x") @interface ListenerMeta { }
}
