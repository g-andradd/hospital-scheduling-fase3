package br.com.fiap.hospital.arquitetura.sintetico.a6.negativo.infrastructure.web;

import br.com.fiap.hospital.security.JwtService;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OutroControllerComJwt {
    private JwtService jwtService;
}
