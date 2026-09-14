package br.com.fiap.hospital.qualidade;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentType;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Leitura de XML offline e segura, so com o JDK.
 *
 * <p>Os gates leem POMs e o relatorio do JaCoCo. Nenhum desses arquivos precisa de DTD
 * externa ou de entidade: o relatorio do JaCoCo declara uma DTD publica, mas o conteudo nao
 * depende dela. Por isso a DTD externa nunca e carregada, entidades externas nunca sao
 * resolvidas e qualquer declaracao de entidade no documento e recusada — um relatorio que
 * precisasse delas nao seria um relatorio legitimo.
 */
public final class XmlSeguro {

    private XmlSeguro() {}

    /** Documento XML recusado: ausente, vazio, ilegivel ou com entidade declarada. */
    public static final class XmlRecusado extends Exception {
        public XmlRecusado(String mensagem, Throwable causa) {
            super(mensagem, causa);
        }
    }

    public static Document ler(Path arquivo) throws XmlRecusado {
        if (arquivo == null || !Files.isRegularFile(arquivo)) {
            throw new XmlRecusado("arquivo ausente: " + arquivo, null);
        }
        try {
            if (Files.size(arquivo) == 0) {
                throw new XmlRecusado("arquivo vazio: " + arquivo, null);
            }
            return lerTexto(Files.readString(arquivo));
        } catch (IOException e) {
            throw new XmlRecusado("arquivo ilegivel: " + arquivo, e);
        }
    }

    public static Document lerTexto(String xml) throws XmlRecusado {
        if (xml == null || xml.isBlank()) {
            throw new XmlRecusado("documento vazio", null);
        }
        try {
            DocumentBuilder construtor = fabrica().newDocumentBuilder();
            construtor.setErrorHandler(new ErrorHandler() {
                @Override public void warning(SAXParseException e) { }
                @Override public void error(SAXParseException e) throws SAXException { throw e; }
                @Override public void fatalError(SAXParseException e) throws SAXException { throw e; }
            });
            Document documento = construtor.parse(new InputSource(new StringReader(xml)));
            DocumentType tipo = documento.getDoctype();
            if (tipo != null && (tipo.getEntities().getLength() > 0
                    || (tipo.getInternalSubset() != null && tipo.getInternalSubset().contains("ENTITY")))) {
                throw new XmlRecusado("documento declara entidade: recusado", null);
            }
            // Com DTD externa que nao e lida, uma referencia a entidade nao declarada nao e erro
            // de boa formacao para um parser nao validante: ela vira um no sem conteudo. Um
            // relatorio que dependesse de uma entidade de fora nao e legitimo.
            if (referenciaEntidade(documento)) {
                throw new XmlRecusado("documento referencia entidade: recusado", null);
            }
            return documento;
        } catch (SAXException | IOException | ParserConfigurationException e) {
            throw new XmlRecusado("documento ilegivel: " + e.getMessage(), e);
        }
    }

    private static boolean referenciaEntidade(Node no) {
        for (Node filho = no.getFirstChild(); filho != null; filho = filho.getNextSibling()) {
            if (filho.getNodeType() == Node.ENTITY_REFERENCE_NODE || referenciaEntidade(filho)) {
                return true;
            }
        }
        return false;
    }

    static DocumentBuilderFactory fabrica() throws ParserConfigurationException {
        DocumentBuilderFactory fabrica = DocumentBuilderFactory.newInstance();
        fabrica.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        fabrica.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        fabrica.setFeature("http://xml.org/sax/features/external-general-entities", false);
        fabrica.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        fabrica.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        fabrica.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        fabrica.setXIncludeAware(false);
        fabrica.setExpandEntityReferences(false);
        fabrica.setNamespaceAware(false);
        fabrica.setValidating(false);
        return fabrica;
    }

    /** Filhos diretos com o nome dado, em ordem. */
    public static List<Element> filhos(Element pai, String nome) {
        List<Element> encontrados = new ArrayList<>();
        if (pai == null) {
            return encontrados;
        }
        for (Node no = pai.getFirstChild(); no != null; no = no.getNextSibling()) {
            if (no instanceof Element elemento && elemento.getTagName().equals(nome)) {
                encontrados.add(elemento);
            }
        }
        return encontrados;
    }

    public static Element filho(Element pai, String nome) {
        List<Element> encontrados = filhos(pai, nome);
        return encontrados.isEmpty() ? null : encontrados.getFirst();
    }

    /** Texto aparado de um filho direto, ou nulo se ele nao existir. */
    public static String texto(Element pai, String nome) {
        Element elemento = filho(pai, nome);
        return elemento == null ? null : elemento.getTextContent().trim();
    }

    /** Segue um caminho de filhos diretos. */
    public static Element caminho(Element inicio, String... nomes) {
        Element atual = inicio;
        for (String nome : nomes) {
            atual = filho(atual, nome);
            if (atual == null) {
                return null;
            }
        }
        return atual;
    }
}
