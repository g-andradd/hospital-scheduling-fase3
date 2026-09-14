package br.com.fiap.hospital.arquitetura.sintetico.a6.positivo.infrastructure.web;

import br.com.fiap.hospital.security.JwtService;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AutenticacaoController {
    private JwtService jwtService;
}
