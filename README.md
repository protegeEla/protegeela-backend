# ProtegeEla — backend

API Java 17 com Spring Boot, PostgreSQL, Flyway, Redis e WebSocket.

## Executar frontend e backend juntos com Docker

O arquivo Compose fica em `protegeela-frontend/compose.yaml` e inicia os
containers `frontend`, `backend` e `redis` juntos. O PostgreSQL `protegeela` do
pgAdmin continua no Windows para manter o banco existente.

No CMD, execute a partir da pasta do frontend:

```bat
cd /d "%USERPROFILE%\Documents\protegeela\protegeela-frontend"
docker compose up --build
```

O frontend abre em `http://localhost:3000` e a API em
`http://localhost:8081/api`. Consulte o [guia Docker completo](../protegeela-frontend/README_DOCKER.md).

## Executar no Windows

Com um JDK 17 e um PostgreSQL acessível. Por padrão, o backend conecta em
`localhost:5432/protegeela`, com usuário `postgres` e senha `luiz`. Para um
servidor remoto, defina o hostname informado pelo provedor e, se necessário,
a porta, nome do banco, usuário e senha antes de iniciar:

```powershell
$env:DB_HOST = "host-fornecido-pelo-provedor"
$env:DB_PORT = "5432"
$env:DB_NAME = "protegeela"
$env:DB_USERNAME = "postgres"
$env:DB_PASSWORD = "senha-do-banco"
```

Se usar Git Bash, configure as mesmas variáveis assim:

```bash
export DB_HOST='host-fornecido-pelo-provedor'
export DB_PORT='5432'
export DB_NAME='protegeela'
export DB_USERNAME='postgres'
export DB_PASSWORD='senha-do-banco'
./mvnw spring-boot:run
```

Substitua os exemplos pelos dados do provedor. A aplicação monta a URL JDBC a
partir dessas variáveis, portanto `DB_HOST` deve conter somente o hostname, sem
`postgres://`, `postgresql://` ou `jdbc:`. Não configure `DB_URL`; um valor
nesse formato não JDBC causava o erro `'url' must start with "jdbc"`. Se o
provedor fornecer uma URI completa, use o hostname, a porta, o nome do banco,
usuário e senha correspondentes nos campos acima. `DB_HOST` e `DB_PORT` podem
ser omitidos para usar o PostgreSQL local. As variáveis valem apenas para o
terminal atual; não salve credenciais remotas no repositório.

Na pasta `protegeela-backend`, faça a primeira inicialização com `clean` para
remover migrations antigas que possam ter ficado em `target/classes` após a
consolidação dos scripts. Essa limpeza remove apenas arquivos gerados pelo
Maven; não apaga nem recria o banco:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start-redis.ps1
./mvnw.cmd clean spring-boot:run
```

No Git Bash, inicie o Redis pelo PowerShell e rode o Maven Wrapper assim:

```bash
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/start-redis.ps1
./mvnw clean spring-boot:run
```

O log deve mostrar `jdbc:postgresql://<host>:<porta>/<banco>`. Se mostrar
`localhost` quando pretende usar um banco remoto, defina `DB_HOST` no mesmo
terminal do Git Bash antes de iniciar (veja os exemplos acima).

O script inicia o Redis local sem Docker. Mantenha o terminal do backend aberto;
use `Ctrl+C` para encerrar. Confira `http://localhost:8080/api/health`.

O Spring não carrega arquivos `.env` automaticamente. Configure as variáveis no
ambiente da sessão antes de iniciar o backend e não as inclua no Git.

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

Configure `FRONTEND_BASE_URL` para que os links de convite apontem ao frontend
correto (o padrão local é `http://localhost:3000`). O servidor verifica
acompanhamentos vencidos a cada 15 segundos; ajuste com
`CHECK_IN_SCAN_DELAY_MS` quando necessário.
O modo “Cheguei bem” pode gerar um link privado de trajeto usando
`FRONTEND_BASE_URL`. O navegador envia novas posições enquanto o frontend está
aberto; o prazo e a criação de alerta continuam sendo controlados pelo backend.

## Configuração de e-mail

As propriedades SMTP ficam separadas em `src/main/resources/application-mail.properties`;
segredos são lidos do ambiente, nunca gravados nos arquivos versionados.
Confirmação por e-mail permanece desativada por padrão. Para reativá-la no
Docker, configure `EMAIL_VERIFICATION_ENABLED=true` e as variáveis `MAIL_*` no
arquivo `.env` usado pelo Compose. Sem essa opção, novos cadastros entram sem
precisar de envio de e-mail.

## Organização do código

- `auth`: cadastro, login, sessões e confirmação de e-mail.
- `user`: entidade, repositório e serviço de usuários.
- `alert`, `checkin`, `contact`: fluxos do domínio e seus endpoints.
- `support`: catálogos e busca de pontos de apoio.
- `realtime`: WebSocket e distribuição de eventos.
- `config`: segurança, senha e configuração do WebSocket.
- `shared`: erros e estruturas compartilhadas da API.
- `src/main/resources/db/migration`: migrations Flyway em ordem de versão.

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
