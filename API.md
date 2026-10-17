# API do ProtegeEla

Atualizações ao vivo do mapa e cache: [Redis e WebSocket](REDIS_WEBSOCKET.md).
Login/cadastro possuem limites de tentativas por 15 minutos e retornam 429 com `Retry-After`
quando excedidos. Detalhes e pendências: [revisão de segurança](SECURITY_REVIEW.md).

Base local: `http://localhost:8080/api`. Exceto saúde, cadastro, login e resposta
a convite, as rotas exigem `Authorization: Bearer <token>`.
IDs são UUIDs. Datas são ISO 8601 UTC. Listas retornam `{"items": [...]}`.
Erros de validação retornam 400; autenticação 401; permissão administrativa 403;
recurso ausente ou pertencente a outra conta 404; conflito de estado 409.

## Autenticação e perfil

| Método | Rota | Corpo JSON / comportamento |
| --- | --- | --- |
| GET | `/health` | Estado público da API: `{"status":"ok"}` |
| POST | `/auth/register` | `name`, `email`, `phone`, `password`; com a confirmação desativada, cria a conta e retorna `token` e `profile`; se ativada, envia código e retorna estado pendente |
| POST | `/auth/verify-email` | Com a confirmação ativada: `email`, `code` (6 dígitos); confirma o e-mail e retorna `token` e `profile` |
| POST | `/auth/resend-verification` | Com a confirmação ativada: `email`; solicita outro código sem revelar se a conta existe |
| POST | `/auth/login` | `email`, `password`; retorna `token` e `profile` |
| POST | `/auth/logout` | Revoga o token atual; retorna 204 |
| GET | `/profile` | Perfil da pessoa autenticada |
| PUT | `/profile` | `full_name`, `phone`, `privacy_mode` |
| PATCH | `/profile/privacy` | `privacy_mode`: `standard` ou `discreet` |

O perfil retorna `id`, `full_name`, `phone`, `role` e `privacy_mode`.
Novas contas recebem `USER`; o cliente não escolhe ID nem role. E-mails são
normalizados em minúsculas e únicos no banco. Senhas usam BCrypt, com mínimo
de oito caracteres e máximo de 72 bytes UTF-8.

Confirmação de e-mail está desativada por padrão (`EMAIL_VERIFICATION_ENABLED=false`),
então o cadastro funciona sem SMTP. Para retomar esse recurso, ative a variável e
configure `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD` e `MAIL_FROM`.
O código expira em 10 minutos. A migration aditiva `V6__email_verification.sql`
preserva os campos necessários e mantém contas existentes confirmadas.

Após confirmar o código, tokens aleatórios expiram em 12 horas e somente seu
hash SHA-256 é persistido. Não há cookies de autenticação. O frontend mantém o
token apenas em memória; recarregar a página exige novo login. Recuperação de
senha ainda não está disponível.

## Contatos

| Método | Rota | Função |
| --- | --- | --- |
| GET | `/contacts` | Lista os contatos da conta |
| POST | `/contacts` | Cria contato (201) |
| PUT | `/contacts/{id}` | Edita nome, telefone, e-mail e relação |
| PATCH | `/contacts/{id}/location-permission` | Salva `can_view_exact_location` |
| POST | `/contacts/{id}/invitation` | Gera link temporário de convite para compartilhamento manual |
| GET | `/contact-invitations/{token}` | Consulta pública do convite |
| POST | `/contact-invitations/{token}/respond` | Aceita ou recusa publicamente o convite |
| DELETE | `/contacts/{id}` | Remove contato (204) |

Corpo de criação/edição:

```json
{
  "name": "Nome do contato",
  "phone": "11999999999",
  "email": null,
  "relationship": "amiga",
  "can_view_exact_location": false,
  "preferred_channel": "whatsapp"
}
```

O primeiro contato é principal. Ao removê-lo, o mais antigo assume essa posição.
Há limite de 20 contatos por conta. O proprietário vem da sessão, nunca do corpo.
O canal pode ser `whatsapp`, `sms`, `email` ou `call`. Criar o link muda o estado
para `pending`; o contato muda para `accepted` ou `declined` ao responder. O link
expira em sete dias e somente seu hash é armazenado. Sem provedor externo, o
frontend abre o canal escolhido para compartilhamento manual. A permissão de
localização não libera coordenadas automaticamente para terceiros.

## Acompanhamento “Cheguei bem”

| Método | Rota | Função |
| --- | --- | --- |
| POST | `/safety-checkins` | Inicia com prazo, compartilhamento opcional e primeira localização |
| GET | `/safety-checkins/current` | Retorna o acompanhamento ativo ou `null` |
| POST | `/safety-checkins/complete` | Confirma chegada segura |
| POST | `/safety-checkins/cancel` | Cancela o acompanhamento |
| POST | `/safety-checkins/current/share` | Ativa o compartilhamento e gera um novo link privado |
| PUT | `/safety-checkins/current/location` | Registra uma posição do trajeto (204) |
| GET | `/safety-checkins/shared/{token}` | Exibe estado e percurso pelo link, sem login |

O tempo definitivo roda no backend. Após o horário previsto e a tolerância, uma
tarefa cria um alerta privado `missed_check_in`, salvo se já houver alerta ativo.
Quando `share_location=true`, a primeira posição pode ser enviada em `location`.
As posições seguintes são autenticadas; o visualizador usa um token aleatório cujo
hash é armazenado no banco. A resposta pública não pode ser armazenada em cache.
Ao confirmar ou cancelar, o compartilhamento é encerrado e o percurso exato é
removido. O link expira 24 horas depois do fim previsto.
Enquanto WhatsApp/SMS/e-mail não estiverem configurados, esse alerta fica
registrado, mas nenhuma mensagem externa é enviada.

## Alertas

| Método | Rota | Função |
| --- | --- | --- |
| POST | `/alerts` | Cria ou recupera a mesma solicitação (201, `{"alert": {...}}`) |
| GET | `/alerts/active` | Retorna `{"alert": {...}}` ou `{"alert": null}` |
| GET | `/alerts/{id}` | Consulta o próprio alerta |
| GET | `/alerts/{id}/location` | Retorna `{"location": {...}}` ou `null` |
| PUT | `/alerts/{id}/location` | Atualiza a localização mais recente (204) |
| POST | `/alerts/{id}/close` | Encerra o alerta e elimina as coordenadas exatas |
| GET | `/alerts/{id}/notifications` | Informa `{"status":"not_configured","sent":false}` |
| GET | `/alerts/map?south=-4&west=-61&north=-2&east=-59` | Alertas compartilhados na região |

Corpo de criação:

```json
{
  "client_request_id": "11111111-1111-4111-8111-111111111111",
  "alert_type": "immediate_danger",
  "is_silent": false,
  "public_visibility": false,
  "location_status": "location_unavailable",
  "location": null
}
```

Para enviar localização, use o objeto abaixo no campo `location` tanto na criação
quanto na atualização. Substitua a data pela captura atual:

```json
{
  "latitude": 0.0123,
  "longitude": 0.0345,
  "accuracy": 10,
  "altitude": null,
  "source": "browser_geolocation",
  "captured_at": "2026-09-26T12:00:00Z"
}
```

- `client_request_id` deve ser reutilizado ao repetir uma solicitação. Mesmo sob
  concorrência, a repetição retorna o mesmo alerta; não reabre alertas encerrados.
- Uma conta pode ter um único alerta ativo. Outra solicitação recebe 409.
- Tipos: `immediate_danger`, `domestic_violence`, `harassment`, `medical`, `other`.
- Encerramento: `{"reason":"safe"}`, `help_received`, `false_alarm` ou `cancelled`.
- Atualizações antigas não sobrescrevem capturas novas. Capturas com mais de
  24 horas ou mais de cinco minutos no futuro são rejeitadas.
- O mapa exige autorização explícita (`public_visibility=true`). O frontend
  apresenta essa opção desmarcada. `is_silent=true` sempre impede publicação.
- O mapa retorna no máximo 100 alertas ativos, sem usuário, contato ou coordenada
  exata. Os centros usam uma grade fixa de 0,02 grau e raio indicativo de 1.600 m.
- Apenas o proprietário consulta o detalhe e a localização exata. Encerrar remove
  a localização exata e as coordenadas do mapa. Não há rastreamento automático em
  segundo plano: a atualização é acionada pela pessoa na tela de alerta.
- Notificações externas e atendimento oficial não são acionados por estes endpoints.

## Catálogos e administração

| Método | Rota | Função |
| --- | --- | --- |
| GET | `/support-points` | Pontos cadastrados (até 1.000) |
| GET | `/safety-content` | Orientações publicadas (até 200) |
| GET | `/emergency-services/active` | Primeiro serviço ativo ou `{"service":null}` |
| GET | `/admin/metrics` | Contagens de alertas e pontos verificados |
| POST / PUT | `/admin/support-points` / `/admin/support-points/{id}` | Cadastra/edita ponto |
| DELETE | `/admin/support-points/{id}` | Remove ponto |
| POST / PUT | `/admin/safety-content` / `/admin/safety-content/{id}` | Cadastra/edita orientação |
| POST / PUT | `/admin/emergency-services` / `/admin/emergency-services/{id}` | Cadastra/edita serviço |

As rotas `/admin/**` exigem role `ADMIN` no servidor. Cadastros públicos continuam
recebendo `USER`; o cliente não pode escolher sua role. A atribuição administrativa
é uma operação do responsável pelo banco. Os catálogos começam vazios, sem locais
ou telefones fictícios publicados. Antes de publicar, revise as informações reais.

Campos de ponto: `name`, `category`, `address`, `city`, `state` (UF), `latitude`,
`longitude`, `is_verified`; opcionais: `description`, `phone`, `opening_hours`,
`website` (HTTP/HTTPS), `accessibility_info`.

Campos de orientação: `title`, `summary`, `content`, `category`, `is_published`;
opcionais: `source_name` e `source_url` (HTTP/HTTPS). `updated_at` é mantido pelo
servidor.
Use `is_published=false` para retirar uma orientação da lista pública.

Campos de serviço: `name`, `phone`, `description`, `region`, `is_active`.
Use `is_active=false` para desativar. O cadastro não inicia chamadas telefônicas.

## Banco e execução

O Flyway executa a migration consolidada V5__complete_application_schema.sql em protegeela_schema_history e preserva os registros anteriores. O script cria tabelas ausentes e adiciona colunas necessarias sem apagar dados.

Em banco novo, crie o database e configure `DB_HOST`, `DB_PORT` e `DB_PASSWORD`.
Em banco existente, mantenha sua estrutura de contas compatível com `User`.
Não apague o histórico de migrations para reaplicar uma migração já executada.

Instruções de execução e CORS: [README](README.md).
