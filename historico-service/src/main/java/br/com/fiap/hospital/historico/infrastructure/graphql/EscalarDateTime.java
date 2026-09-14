package br.com.fiap.hospital.historico.infrastructure.graphql;

import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;

/**
 * O scalar DateTime, definido aqui em vez de vir de biblioteca.
 *
 * <p>O ganho nao e evitar uma dependencia: e controlar a falha. Instante malformado tem de
 * ser recusado na analise do documento, como requisicao invalida, e nao virar excecao
 * generica dentro do resolver — que e onde ele apareceria se a conversao ficasse no
 * mapeamento do argumento.
 */
@Configuration
class EscalarDateTime {

    static final GraphQLScalarType TIPO = GraphQLScalarType.newScalar()
            .name("DateTime")
            .description("Instante em ISO-8601 com deslocamento.")
            .coercing(new Coercing<OffsetDateTime, String>() {

                /**
                 * O read model guarda dois formatos, por razoes do M08: {@code data_hora} e
                 * o instante do fato de negocio, mapeado como {@code OffsetDateTime}, e
                 * {@code criado_em}/{@code atualizado_em} sao {@code Instant}. Os dois
                 * representam instantes e saem no mesmo formato; recusar {@code Instant}
                 * aqui tornaria dois campos do snapshot impossiveis de consultar.
                 */
                @Override
                public String serialize(Object entrada) {
                    if (entrada instanceof OffsetDateTime instante) {
                        return instante.toString();
                    }
                    if (entrada instanceof Instant instante) {
                        return instante.atOffset(ZoneOffset.UTC).toString();
                    }
                    throw new CoercingSerializeException("Valor nao e um DateTime.");
                }

                @Override
                public OffsetDateTime parseValue(Object entrada) {
                    return converter(entrada.toString(),
                            m -> new CoercingParseValueException(m));
                }

                @Override
                public OffsetDateTime parseLiteral(Object entrada) {
                    if (!(entrada instanceof StringValue texto)) {
                        throw new CoercingParseLiteralException(
                                "DateTime precisa vir como texto ISO-8601.");
                    }
                    return converter(texto.getValue(),
                            m -> new CoercingParseLiteralException(m));
                }

                @Override
                public Value<?> valueToLiteral(Object entrada) {
                    return StringValue.newStringValue(serialize(entrada)).build();
                }

                private OffsetDateTime converter(
                        String texto, java.util.function.Function<String, RuntimeException> erro) {
                    try {
                        OffsetDateTime instante = OffsetDateTime.parse(texto);
                        // A faixa e verificada aqui, na coercao, e nao no resolver: e o
                        // ponto por onde passam o filtro e a data corrigida, e recusar
                        // depois ja seria com o valor a caminho do SQL.
                        if (!FaixaTemporalSuportada.suporta(instante)) {
                            throw erro.apply(FaixaTemporalSuportada.mensagemDeRecusa());
                        }
                        return instante;
                    } catch (DateTimeParseException e) {
                        throw erro.apply("DateTime invalido: esperado ISO-8601 com deslocamento.");
                    }
                }
            })
            .build();

    @Bean
    RuntimeWiringConfigurer configurarEscalarDateTime() {
        return wiring -> wiring.scalar(TIPO);
    }
}
