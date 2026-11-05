# DualCam

Câmera manual para Android (estilo Blackmagic Camera) que **grava horizontal e vertical ao mesmo tempo**
e entrega **dois arquivos**: `DualCam_..._H.mp4` (16:9, quadro inteiro) e `DualCam_..._V.mp4` (9:16, recorte central do mesmo quadro).
Os dois saem do mesmo sensor, no mesmo instante, então ficam sincronizados quadro a quadro.

## Como gerar o APK (sem Android Studio)

1. Crie um repositório no GitHub e suba esta pasta:
   ```bash
   git init
   git add .
   git commit -m "DualCam"
   git branch -M main
   git remote add origin https://github.com/SEU_USUARIO/DualCam.git
   git push -u origin main
   ```
2. Abra a aba **Actions** do repositório: o workflow **Build APK** roda sozinho (leva ~3-5 min).
3. Baixe o APK em **Releases** (pelo próprio celular) ou em Actions → execução → **Artifacts → DualCam-apk**.
4. No celular, abra o `DualCam.apk` e permita "instalar apps desconhecidos".

Se o build falhar, abra a execução em Actions, copie o erro e me mande.

## Uso

- **Gravar:** botão vermelho à direita. A moldura 9:16 mostra exatamente o que vai para o arquivo vertical.
- **Barra superior:** LENTE (troca entre as câmeras traseiras que o aparelho libera), resolução, fps, codec (H.265/H.264), ZEBRA (listras vermelhas nas áreas estouradas) e GRADE.
- **Barra inferior:** EV, ISO, OBTURADOR, TEMP (balanço de branco em K) e FOCO. Toque num item para abrir o slider; mexer no slider já passa para manual; o botão AUTO volta ao automático.
- Os arquivos ficam em **Movies/DualCam** (aparecem na Galeria).

## Observações importantes

- **"Sensor full frame":** celular não tem sensor 35 mm. O app usa o sensor inteiro, sem zoom digital, e você escolhe a resolução (inclui 4:3 quando o aparelho permite).
- Gravar dois vídeos em 4K aquece e exige muito do aparelho. Comece em 1080p/30.
- ISO, obturador, foco e balanço manual dependem do aparelho liberar o modo `MANUAL_SENSOR` / `MANUAL_POST_PROCESSING` para apps; quando não libera, o app avisa.
- O balanço de branco em Kelvin é aproximado (relativo ao automático medido na hora). Dá para calibrar melhor com as matrizes de cor do sensor numa próxima versão.
- Android 10 ou superior (minSdk 29). Tela travada em paisagem.

## Estrutura

- `CameraEngine.kt` — Camera2 (ISO, obturador, WB, foco, FPS).
- `VideoPipeline.kt` — OpenGL: preview + encoder horizontal + encoder vertical (recorte 9:16).
- `Recorder.kt`, `VideoEncoder.kt`, `AudioPump.kt`, `MuxerSink.kt` — gravação, áudio AAC compartilhado e arquivos .mp4.
- `MainActivity.kt` — interface e controles.

## Próximos passos sugeridos

Toque para focar, histograma, recorte vertical arrastável, perfil LOG/10-bit, áudio externo, lentes físicas de câmeras lógicas, calibração do WB.
