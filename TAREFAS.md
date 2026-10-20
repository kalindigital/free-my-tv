# Tarefas

- [x] Instalar o Free My TV na TV TCL (192.168.1.5), configurado para Maringá-PR (°C, BRL, região BR, pt-BR, chave TMDB).
- [x] Protetor de tela informativo em fundo preto (`InfoView`): hora e data, clima atual com máxima/mínima e próximas 6 horas, previsão de 7 dias (Open-Meteo `forecast_days=7`) e 5 manchetes do Google Notícias Brasil (`News` + `Rss`), com deslocamento de pixels a cada minuto contra burn-in.
- [x] Teste do parser de RSS (`tests/run.sh`).
- [x] Painel próprio para o botão de menu do controle (`PanelActivity`): Wi-Fi, controles e Bluetooth, entradas, imagem, Free My TV e configurações (PR #2).
- [x] Ler notícias no protetor de tela: ▲▼ escolhem a manchete, OK abre a matéria com texto e fotos (`Reader` + `ArticleView`), ▲▼/◀▶ rolam, Voltar retorna (PR #3).
- [x] Integração do Cinemora no Free My TV (ver abaixo).
- [x] Free My TV: abas Filmes e Séries com as fileiras do Cinemora (mesmas categorias e capas); OK abre o título no detalhe do Cinemora (`Cinemora.java`, PR #4).
- [x] Proteção: Início se declara opaco a cada retorno (o sistema o deixou "transparente" duas vezes na TCL carregada).
