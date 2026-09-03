package br.com.fiap.hospital.agendamento.domain;

import java.util.regex.Pattern;

/**
 * Endereco de e-mail valido, normalizado para minusculas e sem espacos nas bordas.
 *
 * <p>A validacao e deliberadamente conservadora: exige parte local, arroba e dominio
 * com ao menos um ponto. Nao tenta implementar a RFC 5322 inteira, que aceita formas
 * que nenhum servidor de e-mail hospitalar vai ver.
 */
public record Email(String valor) {

    private static final Pattern FORMATO =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    public Email {
        valor = normalizarEValidar(valor);
    }

    private static String normalizarEValidar(String bruto) {
        if (bruto == null || bruto.isBlank()) {
            throw new IllegalArgumentException("O e-mail e obrigatorio");
        }
        String normalizado = bruto.trim().toLowerCase();
        if (!FORMATO.matcher(normalizado).matches()) {
            throw new IllegalArgumentException("E-mail invalido: " + bruto);
        }
        return normalizado;
    }
}
