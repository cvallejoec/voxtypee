# VoxTypee

Dictado por voz con IA sobre **cualquier teclado** de Android. Sigues usando tu
teclado de siempre (Gboard, Samsung, SwiftKey…); cuando se abre, aparece una
burbuja con un micrófono. La tocas, hablas, y el texto se escribe donde está el
cursor. La transcripción la hace la API de OpenAI (o una compatible) con **tu
propia API key**.

Creado por **Deibee**.

```
  teclado abierto        grabando             transcribiendo       listo
 ┌──────────────┐    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
 │ Hola, |      │    │ Hola, |      │    │ Hola, |      │    │ Hola, nos    │
 │         (🎤) │    │   ▁▃▅▃  (⏺) │    │         (⟳) │    │ vemos a las 5│
 │ [ teclado ]  │    │ [ teclado ]  │    │ [ teclado ]  │    │ [ teclado ]  │
 └──────────────┘    └──────────────┘    └──────────────┘    └──────────────┘
```

## Instalar

1. En el teléfono, abre [Releases](https://github.com/cvallejoec/voxtypee/releases/latest)
   y descarga `VoxTypee-X.Y.Z.apk`. Ábrelo y permite instalar apps de esta fuente.
2. Abre VoxTypee y en **Permisos**:
   - **Micrófono → Permitir.**
   - **Accesibilidad → Activar** y enciende VoxTypee. Si el interruptor sale gris
     («ajuste restringido»): Ajustes → Apps → VoxTypee → ⋮ → **Permitir ajustes
     restringidos**, y vuelve a intentar. Android lo pide a toda app que no viene
     de Play Store.
3. En **Proveedor de IA** pega tu API key de OpenAI y toca **Guardar**.
4. Prueba en la tarjeta **Probar**.

Para actualizar, descarga la APK nueva e instálala encima: se conservan los ajustes.

## Ajustes

| Ajuste | Por defecto | Para qué |
|---|---|---|
| API key | — | Se guarda cifrada con el Android Keystore; nunca sale del teléfono salvo hacia el endpoint. |
| Endpoint | `https://api.openai.com` | Cualquier API compatible con `/v1/audio/transcriptions`. |
| Modelo final | `gpt-transcribe` | Transcribe el audio completo al terminar. |
| Modelo en vivo | `gpt-live-transcribe` | Va escribiendo mientras hablas (Realtime API por WebSocket). |
| Idioma | Español | O detección automática. |
| Texto en vivo | En el campo | **En el campo**: se escribe mientras hablas y se corrige al final. **Franja**: se ve sobre el teclado y se escribe al terminar. **Apagado**: solo al terminar. |
| Pasada final | Encendida | Al terminar, el audio completo pasa por el modelo final y ese texto reemplaza al de en vivo. Más precisa; cuesta ~el doble. Apagada, queda el texto del modelo en vivo. |
| Micrófono | Automático | Usa los audífonos conectados (Bluetooth, USB o cable) y, si no hay, el del teléfono. Con Bluetooth el audio pasa a modo llamada mientras dictas. |
| Modo | Tocar | **Tocar**: toca para empezar y para terminar; arrastra la burbuja para moverla. **Mantener**: habla mientras la mantienes; arrastra afuera para cancelar. |
| Vocabulario | — | Nombres, siglas y jerga; van al modelo como `keywords` (con `prompt` los modelos gpt-*transcribe casi no los respetan). |

## Privacidad

VoxTypee usa la accesibilidad solo para saber si hay un teclado abierto, dibujar
la burbuja y escribir el texto dictado en el campo con el cursor. No lee ni
guarda lo que escribes, y no aparece en campos de contraseña. El audio se graba
solo mientras la burbuja está roja y va directo al endpoint configurado.

## Desarrollo

Requiere JDK 21 y el Android SDK (plataforma 36).

```bash
./gradlew assembleDebug                       # APK de prueba
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s VoxTypee                        # qué está pasando
```

### Publicar una versión

```bash
git tag v1.0.0 && git push origin v1.0.0
```

El workflow `release.yml` compila la APK firmada y la sube a Releases. Usa los
secretos del repo `VOXTYPEE_KEYSTORE_BASE64` y `VOXTYPEE_KEYSTORE_PASSWORD`.
La llave de firma tiene que ser **siempre la misma**: con otra, Android no deja
instalar la actualización encima.
