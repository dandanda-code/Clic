# Clic

App de Android que **hace clic automáticamente cuando la pantalla destella**.

## Cómo funciona

1. Captura la pantalla (MediaProjection) a baja resolución y mide su brillo medio.
2. Si el brillo sube de golpe (más que el umbral, en menos de 300 ms) lo considera un destello.
3. Toca el punto configurado mediante un servicio de accesibilidad (`dispatchGesture`).

## Uso

1. Instala el APK (descárgalo de la pestaña **Actions → Build APK → artifact `clic-apk`**).
2. Abre Clic y pulsa **Activar servicio de accesibilidad**; busca "Clic" y actívalo.
3. Elige el punto del clic (escribe X/Y o pulsa **Elegir punto** y toca la pantalla).
   Si no eliges ninguno, se usa el centro de la pantalla.
4. Ajusta la **sensibilidad** y la **espera mínima entre clics**.
5. Pulsa **Iniciar detección**, acepta el permiso de captura y ve a la app que quieras.
6. Para parar: botón **Detener** en la app o en la notificación.

Requiere Android 8.0 (API 26) o superior.

## Compilar

```bash
./gradlew assembleRelease
```
