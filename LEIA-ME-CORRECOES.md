# DualCam 0.2.0 — revisão

## O que mudou
- Interface com área exclusiva para a imagem, painel lateral de gravação e barras roláveis. O painel de ajustes tem controle deslizante com largura total.
- Menus de seleção de lente, resolução e FPS. RESET restaura exposição, foco e cor automáticos.
- Selecionar e alterar EV retorna à exposição automática; ISO e obturador compartilham o modo manual, como exige a API Camera2.
- Foco manual depende dos modos de foco da lente, sem exigir indevidamente exposição manual.
- Balanço de branco manual aguarda ganhos e matriz de cor reais da câmera, com ganhos limitados. A temperatura é uma aproximação relativa à medição automática.
- Sessões antigas são descartadas durante trocas de lente e reaberturas. A câmera fecha antes da liberação da sua superfície.
- Rotação aplicada nos pixels da prévia e dos dois vídeos; o arquivo deixa de receber uma segunda rotação por metadados. A orientação fica estável durante a gravação.
- FPS e resolução são filtrados pela câmera e pelo encoder, sem inventar 30 fps como alternativa. H.264 é o padrão de compatibilidade.
- Mensagens de falha de renderização e opção de tentar novamente com RESET.
- Bloqueio de mudanças de configuração durante início/finalização da gravação e proteção contra retornos de uma tela já encerrada.

## Gerar o APK
O ZIP contém código-fonte, não um APK pronto. Extraia e coloque o CONTEÚDO de Dualcam-main na raiz do seu repositório, incluindo .github. No GitHub, abra Actions → Build APK → Run workflow. Ao terminar, baixe DualCam-apk em Artifacts e extraia DualCam.apk. O fluxo gera um APK de depuração e não publica Releases automaticamente.

Alternativa: abrir a pasta no Android Studio, instalar o SDK 34 e gerar o APK. Java 17 e Gradle 8.9 são usados no workflow.

## Validação realizada e limites
Foi feita revisão do código e verificação estrutural de XML, referências dos controles e integridade do ZIP. Este ambiente não tem Android SDK, Gradle ou aparelho conectado; não foi possível compilar nem executar o aplicativo. Não há garantia de que a imagem relatada foi resolvida no aparelho até o teste real.

## Teste no celular
1. Abra e permita câmera e microfone. Verifique imagem, proporção e cores; use RESET.
2. Teste EV; depois ISO e obturador, retorno ao AUTO; WB após medição e foco. Controles indisponíveis devem ser identificados. O app não pode desbloquear controles que o fabricante não disponibiliza.
3. Troque lente, resolução e FPS várias vezes. Saia e volte ao app. Não deve ficar preto ou manter uma sessão antiga.
4. Grave pelo menos 10 segundos, pare e reproduza os dois arquivos em Movies/DualCam. Confira orientação, recorte central e áudio.
5. Repita sem permissão de microfone e nos dois lados da posição horizontal. Durante a gravação, a orientação do vídeo permanece fixa.
6. Se dois encoders simultâneos falharem, teste H.264, 1080p/30 ou 720p/30. A capacidade de cada encoder isolado não garante dois encoders em paralelo no aparelho.

Se a imagem continuar estranha, anote modelo do celular, versão do Android, lente/resolução/FPS e se ocorre na prévia, nos arquivos ou em ambos. Um print e uma gravação curta ajudam a identificar particularidades do driver.
