package br.com.fiap.hospital.agendamento.infrastructure.web;

/**
 * Corpo de {@code PATCH /api/v1/consultas/{id}/cancelar}.
 *
 * <p><b>Sem Bean Validation no motivo, de proposito.</b> A obrigatoriedade do motivo e
 * regra de negocio e ja vive na {@code Consulta}, que recusa nulo, vazio, espacos e tab
 * desde o M01. Um {@code @NotBlank} aqui criaria uma segunda definicao da mesma regra
 * numa camada diferente — e as duas ja divergiam: a validacao do DTO respondia 400 e
 * interceptava antes, de modo que a {@code MotivoDeCancelamentoObrigatorioException},
 * mapeada para 422, nunca era alcancavel pela API.
 *
 * <p>Regra de negocio duplicada em duas camadas nao fica sincronizada; fica com duas
 * respostas para o mesmo caso, e a que responde e a de cima.
 */
public record CancelarConsultaRequest(String motivo) {}
