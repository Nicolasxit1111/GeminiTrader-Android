# Gemini Trader (Android)

App que captura a tela do celular a cada fechamento de vela, manda o gráfico para o Gemini
e avisa por notificação (som + vibração) quando sai sinal CIMA ou BAIXO. Depois mede sozinho
se o sinal acertou. **Não envia ordens.** Use em conta demo.

## Como gerar o APK

### Opção A - Android Studio (no PC)
1. Instale o Android Studio e abra esta pasta (File > Open).
2. Espere o Gradle sincronizar (aceite atualizar o plugin se ele sugerir).
3. Build > Build Bundle(s) / APK(s) > Build APK(s).
4. O arquivo fica em `app/build/outputs/apk/debug/app-debug.apk`.

### Opção B - GitHub, sem instalar nada
1. Crie um repositório no GitHub e envie o conteúdo desta pasta (inclusive a pasta oculta `.github`).
2. Aba **Actions** > workflow **Build APK** > aguarde terminar (uns 3 a 6 min).
3. Baixe o artefato **GeminiTrader-apk** (é um zip com o app-debug.apk dentro).

## Instalar no celular
Passe o APK para o celular, abra e permita "instalar apps de fontes desconhecidas".
O Play Protect pode avisar que o app não é conhecido: é normal, o app é seu.

## Usar
1. Abra o app, cole a chave da API do Gemini (aistudio.google.com/apikey) e ajuste as opções.
2. Toque em **Iniciar** e aceite a captura de tela (o Android pede isso a cada início).
3. Em até 10 s o app faz uma captura de TESTE: abra o app da corretora no gráfico do Bitcoin
   (5 min, expiração 5 min) e deixe o gráfico à vista.
4. Volte ao app e olhe a prévia: deve mostrar SÓ o gráfico, com a etiqueta de preço.
   Se cortou errado, toque em Parar, ajuste os % de recorte e inicie de novo.
   O log mostra o preço lido: confira com o que está na tela.
5. Depois disso ele analisa 3 s após cada fechamento de vela. Sinal forte = notificação.
6. **Placar** mostra acertos, intervalo de confiança e ponto de equilíbrio pelo payout.
   **Compartilhar CSVs** exporta resultados.csv e sinais.csv.

## Limitações
- Se o app da corretora bloquear capturas de tela, a imagem vem preta. Só testando para saber.
- Mantenha a orientação do celular (retrato ou paisagem) igual durante a sessão.
- O Android pode encerrar a captura se o sistema precisar de memória. Nesse caso, reinicie.
- A chave da API fica salva só no aparelho (SharedPreferences, sem criptografia).
- Gasta bateria: deixe carregando.
