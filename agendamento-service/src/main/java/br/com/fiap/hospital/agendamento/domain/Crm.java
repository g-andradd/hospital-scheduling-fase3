package br.com.fiap.hospital.agendamento.domain;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Registro no Conselho Regional de Medicina, no formato {@code UF-numero} — por
 * exemplo {@code DF-12345}.
 *
 * <p>A unidade federativa e conferida contra a lista real das 27, e nao apenas
 * contra "duas letras": um CRM de "XX" nao existe, e aceita-lo deixaria passar
 * erro de digitacao que so apareceria muito depois.
 */
public record Crm(String valor) {

    private static final Set<String> UNIDADES_FEDERATIVAS = Set.of(
            "AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO",
            "MA", "MT", "MS", "MG", "PA", "PB", "PR", "PE", "PI",
            "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO");

    private static final Pattern FORMATO = Pattern.compile("^([A-Z]{2})-(\\d{1,6})$");

    public Crm {
        valor = normalizarEValidar(valor);
    }

    private static String normalizarEValidar(String bruto) {
        if (bruto == null || bruto.isBlank()) {
            throw new IllegalArgumentException("O CRM e obrigatorio");
        }
        String normalizado = bruto.trim().toUpperCase();
        Matcher matcher = FORMATO.matcher(normalizado);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "CRM invalido: " + bruto + ". Formato esperado: UF-numero, como DF-12345");
        }
        if (!UNIDADES_FEDERATIVAS.contains(matcher.group(1))) {
            throw new IllegalArgumentException(
                    "CRM invalido: " + bruto + ". Unidade federativa inexistente: " + matcher.group(1));
        }
        return normalizado;
    }

    public String unidadeFederativa() {
        return valor.substring(0, 2);
    }

    public String numero() {
        return valor.substring(3);
    }
}
