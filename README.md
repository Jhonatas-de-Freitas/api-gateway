# api-gateway

A porta de entrada da loja. Tudo que vem de fora passa por aqui: o gateway confere o login (JWT do Keycloak), limita abuso (Redis), protege contra serviço lento ou fora do ar (circuit breaker) e repassa a chamada para o serviço certo.

Ainda estou construindo. A aplicação e os testes já estão aqui. O `docker-compose.yml` para rodar tudo sozinho e o resto deste README vêm a seguir.

## Como testar

```bash
./mvnw verify
```

Os testes de integração sobem Redis e Keycloak com Testcontainers, então precisam de Docker. Sem Docker eles são pulados e rodam só os de unidade.
