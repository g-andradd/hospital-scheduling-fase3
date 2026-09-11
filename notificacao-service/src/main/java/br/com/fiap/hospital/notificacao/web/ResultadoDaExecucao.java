package br.com.fiap.hospital.notificacao.web;

/** Quantos lembretes a execucao confirmou; zero quando nao havia candidato. */
public record ResultadoDaExecucao(int lembretesEnviados) { }
