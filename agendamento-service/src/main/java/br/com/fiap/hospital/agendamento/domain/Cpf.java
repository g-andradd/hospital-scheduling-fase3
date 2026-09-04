package br.com.fiap.hospital.agendamento.domain;

/**
 * CPF valido, armazenado apenas com digitos.
 *
 * <p>Aceita a entrada com ou sem pontuacao e normaliza. Um valor invalido nao chega
 * a ser representado: o construtor recusa com {@link IllegalArgumentException}, que
 * o M03 responde como 400.
 */
public record Cpf(String valor) {

    public Cpf {
        valor = normalizarEValidar(valor);
    }

    private static String normalizarEValidar(String bruto) {
        if (bruto == null || bruto.isBlank()) {
            throw new IllegalArgumentException("O CPF e obrigatorio");
        }

        String digitos = bruto.replaceAll("[^0-9]", "");

        if (digitos.length() != 11) {
            throw new IllegalArgumentException("O CPF deve ter 11 digitos: " + bruto);
        }
        if (digitos.chars().distinct().count() == 1) {
            throw new IllegalArgumentException("CPF invalido: " + bruto);
        }
        if (digitoVerificador(digitos, 9) != caractereComoInt(digitos, 9)
                || digitoVerificador(digitos, 10) != caractereComoInt(digitos, 10)) {
            throw new IllegalArgumentException("CPF invalido: " + bruto);
        }
        return digitos;
    }

    /** Calcula o digito verificador da posicao informada pelo algoritmo modulo 11. */
    private static int digitoVerificador(String digitos, int posicao) {
        int soma = 0;
        int peso = posicao + 1;
        for (int i = 0; i < posicao; i++) {
            soma += caractereComoInt(digitos, i) * peso--;
        }
        int resto = soma % 11;
        return resto < 2 ? 0 : 11 - resto;
    }

    private static int caractereComoInt(String digitos, int indice) {
        return digitos.charAt(indice) - '0';
    }

    /** Representacao mascarada, para exibicao. */
    public String formatado() {
        return valor.substring(0, 3) + "." + valor.substring(3, 6) + "." + valor.substring(6, 9)
                + "-" + valor.substring(9);
    }
}
