# Plano: novo protetor de tela (tela de bloqueio)

## O que muda

- **Topo à esquerda:** a data (dia e mês) em destaque e, logo abaixo, a hora, um pouco menor.
- **Canto inferior direito:** um bloco de clima que alterna a cada minuto entre
  1. a temperatura agora com a previsão das próximas horas, e
  2. a previsão dos próximos dias.
- **Notícias:** ficam ocultas (o código continua, desligado por `InfoView.SHOW_NEWS`), para decidir depois o que entra no lugar.
- **Fundo, escolhido em Ajustes do Início → "Fundo da tela de descanso":**
  - *Fluido* (padrão): manchas de cor que se movem devagar, bem desfocadas e opacas, desenhadas num bitmap pequeno e ampliado (o desfoque sai de graça, sem shader, porque a TV é Android 11).
  - *Paisagem*: fotos em destaque do Wikimedia Commons (`Category:Featured pictures of landscapes`), sem chave.
  - *Espaço*: fotos da NASA (`images-api.nasa.gov`), sem chave.
  - *Vídeo aéreo*: os vídeos da Apple TV (os mesmos do protetor do macOS), da lista pública da Apple, em 1080p
    H.264, por streaming, em loop e sem som. Em "Vídeo da tela de descanso" escolhe-se um deles ou *Aleatório* (um
    novo a cada abertura, nunca o mesmo duas vezes seguidas). Toca num `TextureView` (um `VideoView` ficaria atrás da
    janela). Só esse servidor da Apple é liberado em HTTP (`res/xml/network_security_config.xml`). Gasta ~2,7 GB/h.
  - *Tempo ao vivo* (`WeatherScene`): o céu como está lá fora, pelo código WMO do tempo atual e dia/noite: sol,
    lua e estrelas, nuvens passando, garoa ou chuva (mais gotas quanto mais forte), neve, neblina e relâmpagos.
  - As fotos trocam a cada 5 minutos com transição suave e ganham sombreado só onde há texto.
- **Atalho:** Voltar duas vezes seguidas (em até 1,5 s) na aba Home abre o protetor.

## Tarefas

1. `Prefs.dreamBackground()` e a linha nova em Ajustes do Início.
2. `DreamBackground` (fundo fluido ou foto) por baixo do `InfoView` no `DreamBoard`.
3. `DreamPhotos`: lista de fotos por tema, download e decodificação fora da thread principal, uma foto por vez na memória.
4. `InfoView`: novo layout (data, hora, bloco de clima alternado), notícias ocultas, fundo transparente.
5. `HomeView`: Voltar duas vezes na Home abre o protetor (`Somnambulator` do sistema; se falhar, a prévia nas configurações).
6. Testes das regras puras (alternância por minuto, filtro de proporção, URL grande da NASA).

## Por quê

O protetor fica mais parecido com uma tela de bloqueio de celular: a hora e a data em evidência, o clima discreto num
canto, e um fundo bonito que não marca a tela (o quadro continua andando alguns pixels por minuto).
