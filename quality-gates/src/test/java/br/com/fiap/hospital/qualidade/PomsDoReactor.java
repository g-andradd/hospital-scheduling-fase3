package br.com.fiap.hospital.qualidade;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Carrega os POMs reais do reactor e devolve copias para os negativos mutarem.
 *
 * <p>Os negativos partem sempre do POM real, e nao de um POM escrito a mao: assim cada um
 * prova que a regra reprova exatamente a mutacao aplicada, e nao uma diferenca acidental
 * entre um exemplo inventado e o projeto de verdade.
 */
final class PomsDoReactor {

    /** O surefire roda com o diretorio do modulo como diretorio de trabalho. */
    static final Path RAIZ = Path.of("..").toAbsolutePath().normalize();

    private PomsDoReactor() {}

    static Document raiz() {
        return ler(RAIZ.resolve("pom.xml"));
    }

    static Map<String, Document> modulos() {
        Map<String, Document> modulos = new LinkedHashMap<>();
        for (Element modulo : XmlSeguro.filhos(
                XmlSeguro.filho(raiz().getDocumentElement(), "modules"), "module")) {
            String nome = modulo.getTextContent().trim();
            modulos.put(nome, ler(RAIZ.resolve(nome).resolve("pom.xml")));
        }
        return modulos;
    }

    static Document ler(Path pom) {
        try {
            return XmlSeguro.ler(pom);
        } catch (XmlSeguro.XmlRecusado e) {
            throw new IllegalStateException(e);
        }
    }

    static Element elemento(Document documento, String... caminho) {
        return XmlSeguro.caminho(documento.getDocumentElement(), caminho);
    }

    static Element plugin(Element plugins, String artifactId) {
        return XmlSeguro.filhos(plugins, "plugin").stream()
                .filter(p -> artifactId.equals(XmlSeguro.texto(p, "artifactId")))
                .findFirst().orElseThrow();
    }

    static void remover(Element elemento) {
        elemento.getParentNode().removeChild(elemento);
    }

    static Map<String, Element> projetos(Map<String, Document> documentos) {
        Map<String, Element> projetos = new LinkedHashMap<>();
        documentos.forEach((nome, documento) -> projetos.put(nome, documento.getDocumentElement()));
        return projetos;
    }

    /** Acrescenta ao pai um filho com texto, e o devolve. */
    static Element acrescentar(Element pai, String nome, String texto) {
        Element filho = pai.getOwnerDocument().createElement(nome);
        if (texto != null) {
            filho.setTextContent(texto);
        }
        pai.appendChild(filho);
        return filho;
    }

    /** Acrescenta ao pai um filho vazio, e o devolve. */
    static Element acrescentar(Element pai, String nome) {
        return acrescentar(pai, nome, null);
    }

    /** Devolve o filho com o nome, criando-o se nao existir. */
    static Element garantir(Element pai, String nome) {
        Element existente = XmlSeguro.filho(pai, nome);
        return existente != null ? existente : acrescentar(pai, nome);
    }
}
