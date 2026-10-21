# Plano: tela Tempo completa

OK no cartão de tempo da Home abre a tela **Tempo**, que imita o app Tempo do iPhone, com três páginas trocadas por ◀▶.

## Páginas
- **Previsão:** a cidade, a temperatura agora, a condição, a máxima e a mínima. Mostra também as próximas 24 horas (hora, ícone, temperatura e chance de chuva) e os próximos 10 dias (ícone, chance de chuva e barra de mínima a máxima na escala da semana). Os blocos de detalhe são sensação, umidade, vento (velocidade, rajada e seta da direção), UV, pressão, visibilidade, chuva (hoje e amanhã), ponto de orvalho, nascer e pôr do sol.
- **Mapas:** ▲▼ trocam a camada, OK aproxima ou afasta.
  - **Chuva:** radar animado do RainViewer, com as últimas 2 horas em quadros de 10 min.
  - **Temperatura:** um campo de cores interpolado de uma grade de pontos da Open-Meteo, com rótulos.
  - **Vento:** setas na direção do vento, com o tamanho e a cor pela velocidade, e rótulos em km/h.
  - O mapa de fundo é o Esri Dark Gray Canvas (sem chave), com os nomes dos lugares por cima, centrado na cidade e com um marcador nela. O Carto passou a exigir chave.
- **Cidades:** a cidade do `config.properties` e as adicionadas, cada uma com a temperatura e a condição de agora. OK abre a previsão dela. "+ Adicionar" abre o teclado da TV e busca pela geocodificação da Open-Meteo. Para remover uma cidade, é preciso segurar OK.

## Dados (sem chave)
- Open-Meteo forecast: `current`, `hourly` e `daily` com 10 dias. Fica 15 min em cache por cidade.
- Open-Meteo geocoding: busca em português.
- Grade do mapa: um pedido só com várias coordenadas (`latitude=a,b,…`), mais ou menos 8×5 pontos.
- RainViewer: `weather-maps.json` com os blocos de 512 px decodificados pela metade, para poupar memória na TV de 2 GB.
- As cidades ficam salvas nas preferências do app.

## Arquivos
- `WeatherCities.java`: as cidades salvas e a busca.
- `WeatherFull.java`: previsão completa e grade do mapa.
- `MapTiles.java`: blocos de mapa e radar em LRU.
- `WeatherActivity.java`: a tela, com o teclado escondido para a busca.
- `HomeView`: OK no cartão de tempo abre a tela.

## Benefícios
Reúne numa tela só a previsão detalhada, a chuva chegando no radar e o tempo em outras cidades, sem abrir apps de terceiros nem ver anúncios.
