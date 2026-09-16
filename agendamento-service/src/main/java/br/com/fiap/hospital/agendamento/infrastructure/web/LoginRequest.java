package br.com.fiap.hospital.agendamento.infrastructure.web;

import jakarta.validation.constraints.NotBlank;

/** Corpo de POST /auth/login. */
public record LoginRequest(
        @NotBlank(message = "O e-mail e obrigatorio") String email,
        @NotBlank(message = "A senha e obrigatoria") String senha) {

    /** Marcador que ocupa o lugar da senha em qualquer representacao textual. */
    private static final String SENHA_OMITIDA = "<omitida>";

    /**
     * Representacao textual que nunca carrega o valor da senha (RNF-01).
     *
     * <p>O {@code toString()} gerado de um record inclui todos os componentes. Isso bastava
     * para violar o requisito sem ninguem escrever uma linha de log: o resolvedor de
     * {@code @RequestBody} do Spring MVC registra o objeto desserializado em DEBUG, e a senha
     * em claro de cada login ia junto. A auditoria de requisitos do M14 encontrou o vazamento.
     *
     * <p>Em INFO, que e o nivel dos profiles de entrega, nada disso aparecia — mas o RNF-01 diz
     * "nunca em log", e depender do nivel configurado e depender de configuracao, nao de
     * garantia. Sobrescrever aqui fecha a origem para qualquer nivel e qualquer componente que
     * venha a imprimir este objeto.
     *
     * <p>Nao afeta desserializacao, validacao, autenticacao nem o contrato JSON do endpoint:
     * nenhum deles passa por {@code toString()}.
     */
    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", senha=" + SENHA_OMITIDA + "]";
    }
}
