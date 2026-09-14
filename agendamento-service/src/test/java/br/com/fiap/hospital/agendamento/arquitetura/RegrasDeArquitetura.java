package br.com.fiap.hospital.agendamento.arquitetura;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.core.domain.properties.CanBeAnnotated.Predicates.annotatedWith;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.GeneralCodingRules;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

/**
 * Regras A1 a A7 do D2, parametrizadas pelo pacote base.
 *
 * <p>O mesmo conjunto roda contra o codigo principal do agendamento e contra as fontes
 * sinteticas de teste, que servem de negativos: uma regra que so e exercida sobre codigo
 * conforme nunca provou que reprova.
 */
final class RegrasDeArquitetura {

    static final String JWT_SERVICE = "br.com.fiap.hospital.security.JwtService";
    static final String METODO_DO_CASO_DE_USO = "executar";

    private final String base;

    RegrasDeArquitetura(String base) {
        this.base = base;
    }

    /** Codigo principal compilado, sem as classes de teste. */
    static JavaClasses importarCodigoPrincipal(String pacote) {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(pacote);
    }

    /** Fontes sinteticas, que vivem justamente entre as classes de teste. */
    static JavaClasses importarSinteticas(String pacote) {
        return new ClassFileImporter().importPackages(pacote);
    }

    /** A1: domain nao depende de application nem de infrastructure; application nao depende de infrastructure. */
    ArchRule direcaoDasCamadas() {
        return CompositeArchRule
                .of(noClasses().that().resideInAPackage(camada("domain"))
                        .should().dependOnClassesThat().resideInAnyPackage(camada("application"), camada("infrastructure")))
                .and(noClasses().that().resideInAPackage(camada("application"))
                        .should().dependOnClassesThat().resideInAPackage(camada("infrastructure")));
    }

    /** A2: domain sem Spring, JPA, Jackson ou Validation. */
    ArchRule dominioSemFramework() {
        return noClasses().that().resideInAPackage(camada("domain"))
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "javax.persistence..",
                        "org.hibernate..",
                        "com.fasterxml.jackson..",
                        "jakarta.validation..",
                        "javax.validation..");
    }

    /**
     * A3: cada {@code *UseCase} declara diretamente exatamente um metodo publico de instancia,
     * {@code executar}. Construtores nao sao metodos; herdados nao sao declarados pela classe;
     * estaticos, sinteticos, bridge e os metodos de {@code Object} nao contam.
     */
    ArchRule umMetodoPublicoPorCasoDeUso() {
        return classes().that().resideInAPackage(camada("application")).and().haveSimpleNameEndingWith("UseCase")
                .should(declararSomenteExecutar());
    }

    /** A4: entidades JPA so na area de persistencia. */
    ArchRule entidadesSoEmPersistencia() {
        return classes().that().areAnnotatedWith("jakarta.persistence.Entity")
                .should().resideInAPackage(camada("infrastructure.persistence"));
    }

    /** A5: controllers sem repositorio, sem porta de saida, sem mensageria e sem caso de uso nu. */
    ArchRule controladoresPassamPeloCasoDeUsoTransacional() {
        DescribedPredicate<JavaClass> proibidas = resideInAnyPackage(
                camada("infrastructure.persistence"), camada("infrastructure.messaging"), camada("domain.port"))
                .or(assignableTo(Repository.class))
                .or(resideInAPackage(camada("application")).and(simpleNameEndingWith("UseCase")));
        return noClasses().that(controladores()).should().dependOnClassesThat(proibidas);
    }

    /** A6: so o controller de autenticacao pode depender do emissor de token. */
    ArchRule emissorDeTokenSoNaAutenticacao() {
        return noClasses()
                .that(controladores().and(not(name(base + ".infrastructure.web.AutenticacaoController"))))
                .should().dependOnClassesThat().haveFullyQualifiedName(JWT_SERVICE);
    }

    /** A7: nada de System.out, System.err ou printStackTrace. */
    ArchRule semSaidaPadrao() {
        return GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
    }

    private String camada(String nome) {
        return base + "." + nome + "..";
    }

    private static DescribedPredicate<JavaClass> controladores() {
        return annotatedWith(RestController.class).<JavaClass>forSubtype().or(annotatedWith(Controller.class));
    }

    private static ArchCondition<JavaClass> declararSomenteExecutar() {
        return new ArchCondition<>("declarar diretamente exatamente um metodo publico de instancia, "
                + METODO_DO_CASO_DE_USO) {
            @Override
            public void check(JavaClass classe, ConditionEvents eventos) {
                List<String> metodos = classe.getMethods().stream()
                        .filter(RegrasDeArquitetura::contaParaCasoDeUso)
                        .map(JavaMethod::getName)
                        .sorted()
                        .toList();
                boolean conforme = metodos.equals(List.of(METODO_DO_CASO_DE_USO));
                eventos.add(new SimpleConditionEvent(classe, conforme, classe.getName()
                        + " declara os metodos publicos de instancia " + metodos
                        + "; esperado somente [" + METODO_DO_CASO_DE_USO + "]"));
            }
        };
    }

    static boolean contaParaCasoDeUso(JavaMethod metodo) {
        Set<JavaModifier> modificadores = metodo.getModifiers();
        return modificadores.contains(JavaModifier.PUBLIC)
                && !modificadores.contains(JavaModifier.STATIC)
                && !modificadores.contains(JavaModifier.SYNTHETIC)
                && !modificadores.contains(JavaModifier.BRIDGE)
                && !deObject(metodo);
    }

    private static boolean deObject(JavaMethod metodo) {
        List<String> parametros = metodo.getRawParameterTypes().stream().map(JavaClass::getName).toList();
        return Arrays.stream(Object.class.getMethods()).anyMatch(doObject -> doObject.getName().equals(metodo.getName())
                && Arrays.stream(doObject.getParameterTypes()).map(Class::getName).toList().equals(parametros));
    }
}
