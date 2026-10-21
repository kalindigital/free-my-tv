# Tarefas

- [x] Instalar o Free My TV na TV TCL (192.168.1.5), configurado para Maringá-PR (°C, BRL, região BR, pt-BR, chave TMDB).
- [x] Protetor de tela informativo em fundo preto (`InfoView`): hora e data, clima atual com máxima/mínima e próximas 6 horas, previsão de 7 dias (Open-Meteo `forecast_days=7`) e 5 manchetes do Google Notícias Brasil (`News` + `Rss`), com deslocamento de pixels a cada minuto contra burn-in.
- [x] Teste do parser de RSS (`tests/run.sh`).
- [x] Painel próprio para o botão de menu do controle (`PanelActivity`): Wi-Fi, controles e Bluetooth, entradas, imagem, Free My TV e configurações (PR #2).
- [x] Ler notícias no protetor de tela: ▲▼ escolhem a manchete, OK abre a matéria com texto e fotos (`Reader` + `ArticleView`), ▲▼/◀▶ rolam, Voltar retorna (PR #3).
- [x] Integração do Cinemora no Free My TV (ver abaixo).
- [x] Free My TV: abas Filmes e Séries com as fileiras do Cinemora (mesmas categorias e capas); OK abre o título no detalhe do Cinemora (`Cinemora.java`, PR #4).
- [x] Proteção: Início se declara opaco a cada retorno (o sistema o deixou "transparente" duas vezes na TCL carregada).
- [x] Saudação no Início ("Bom dia/Boa tarde/Boa noite, Guilherme"), com `name=` no `config.properties`.
- [x] Aba Mercado: Ibovespa, Dólar, Euro, Bitcoin, Ethereum, Nasdaq, S&P 500 e Dow Jones com variação e minigráfico; OK abre o gráfico grande (1D a 5A).
- [x] Aba Buscar: busca no catálogo do Cinemora pelo teclado da TV (com voz), resultados em fileiras, OK abre no Cinemora.
- [x] Voltar de um gráfico, do Cinemora ou de um app retorna ao mesmo ponto do Início (a TV está com "Não manter atividades" ligado).
- [x] Card de receita recorrente (MRR) do Esthetis na aba Explorar, com variação do mês e gráfico de 12 meses (api-esthetis#325).
