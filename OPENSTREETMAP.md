# Busca gratuita de pontos de apoio

O backend usa Overpass API com dados do OpenStreetMap. Não exige chave, cartão ou Google Cloud.
No aplicativo: **Mapa e apoio → Buscar locais no OpenStreetMap**.

Selecione uma categoria e clique em **Buscar perto de mim**, ou informe o nome exato
da cidade (ex.: Manaus) e clique em **Buscar na cidade**. A busca por cidade consulta
áreas administrativas com esse nome; cidades homônimas podem aparecer juntas.
A busca por proximidade ignora o texto da cidade e usa raio padrão de 10 km.
Coordenadas enviadas ao provedor são arredondadas para três casas decimais.

As categorias especializadas usam os nomes cadastrados: delegacias com indicação de
atendimento à mulher, centros de apoio à mulher e Defensoria Pública. Hospitais usam
a classificação `amenity=hospital`. Cobertura incompleta ou nomes diferentes podem
produzir listas vazias. Os resultados não são verificados pelo ProtegeEla.

## Configuração

O endpoint padrão é `https://overpass-api.de/api/interpreter`. Para usar uma instância própria,
configure `OVERPASS_API_URL` no backend e reinicie. O frontend não precisa de configuração adicional.

`POST /api/support-points/places/search` exige Bearer e aceita:

```json
{"category":"hospital","latitude":-3.119,"longitude":-60.021,"radius":10000}
```

Ou `{"category":"women_police","query":"Manaus"}`.
Categorias: `women_police`, `support_center`, `hospital`, `legal` e `all` (mapa).
`query` agora representa o nome exato da cidade; não é busca livre de estabelecimentos.
O raio permitido é de 100 a 50000 metros. Retorno: `items`, `source`, `search_mode`,
`attribution`, `truncated`. Cada item contém `id`, `name`, `address`, `latitude`,
`longitude`, `map_url` e, na busca próxima, `distance_m`.

São consultados até 100 elementos; o mapa (`all`) recebe até 100 e buscas de uma categoria até 20.
O mapa consulta uma vez ao abrir e permite atualização explícita com **Buscar nesta região**.
A consulta seleciona primeiro os elementos da região, depois filtra nomes e categorias nesse conjunto,
evitando buscas de nomes no índice global. Não é um catálogo completo da região.
Os resultados por proximidade são ordenados pela distância entre os elementos retornados.
Há cache de 15 minutos no Redis, compartilhado entre instâncias, e cache local de até 100 consultas.
Com Redis ativo, o limite é de 100 consultas externas por dia com intervalo de 20 segundos.
Se Redis estiver indisponível, o fallback local mantém 100 por dia e intervalo de cinco segundos,
sem coordenação entre instâncias; reiniciar o Java limpa somente o estado local.
Veja [configuração do Redis](REDIS_WEBSOCKET.md). Não use réplicas para ampliar a cota pública.

O servidor público é compartilhado, pode responder 429/504 e não garante disponibilidade.
Não há atualização automática, consultas ao digitar, troca automática de servidores ou repetição automática.
Respostas parciais da Overpass são tratadas como erro. Atribuição e licença ODbL estão disponíveis na tela.
Para um serviço público de uso contínuo, planeje uma instância própria conforme orientação do operador.

Referências:
- https://dev.overpass-api.de/overpass-doc/en/preface/commons.html
- https://wiki.openstreetmap.org/wiki/Overpass_API
- https://www.openstreetmap.org/copyright
