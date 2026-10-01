# ProtegeEla — backend

API Java 17 com Spring Boot, PostgreSQL, Flyway, Redis e WebSocket.

## Executar no Windows

Com um JDK 17 e o PostgreSQL em execução, crie o banco `protegeela`.
Configure `DB_URL`, `DB_USERNAME` e `DB_PASSWORD` no ambiente ou no arquivo
local `application-local.properties`, ignorado pelo Git. A conexão padrão é
`jdbc:postgresql://localhost:5432/protegeela`, com usuário `postgres`.
A configuração principal não contém senha padrão.

Na pasta `protegeela-backend`:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start-redis.ps1
./mvnw.cmd spring-boot:run
```

O script inicia o Redis local sem Docker. Mantenha o terminal do backend aberto;
use `Ctrl+C` para encerrar. Confira `http://localhost:8080/api/health`.

O Spring carrega `application-local.properties` da pasta de execução, mas não
carrega arquivos `.env` automaticamente. Não distribua o arquivo de credenciais.

Se a porta 8080 estiver ocupada, encerre sua execução anterior ou escolha outra:

```powershell
./mvnw.cmd spring-boot:run '-Dspring-boot.run.arguments=--server.port=8081'
```

Ao mudar a porta, ajuste também `API_BASE_URL` no frontend.

## Conectar o frontend

Em outro terminal, na pasta `protegeela-frontend`:

```powershell
flutter pub get
flutter run -d edge --web-port 3000 --dart-define=API_BASE_URL=http://localhost:8080/api
```

O CORS permite `http://localhost:3000` e `http://127.0.0.1:3000`.
Para outras origens, defina `FRONTEND_ORIGINS` separadas por vírgulas antes de
iniciar o backend. Use HTTPS/WSS em produção.

## Compilar

```powershell
./mvnw.cmd package
```

O JAR executável fica em `target/`. No Linux/macOS, use `./mvnw` no lugar de
`./mvnw.cmd`.

## Documentação

- [API](API.md): autenticação, contatos, alertas, catálogos e banco.
- [OpenStreetMap](OPENSTREETMAP.md): busca gratuita de pontos de apoio.
- [Redis e WebSocket](REDIS_WEBSOCKET.md): configuração e atualizações do mapa.
- [Revisão de segurança](SECURITY_REVIEW.md): controles verificados e pendências.
