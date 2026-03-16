# Distriar Repartidor (Android)

App nativa para repartidores integrada al backend.

## Qué incluye
- Login por `usuario` + `contraseña` (no email), usando `/admin/auth/token`.
- Tokens guardados con `EncryptedSharedPreferences`.
- Lectura de pedidos asignados desde `/admin/orders` con auto-asignación por zona.
- Mapa integrado con Google Maps, marcadores de pedidos y depósito.
- Marcado de entregas con `PATCH /orders/{id}/status`.
- Envío de ubicación en tiempo real a `/admin/driver-location`.
- Al salir ~30 metros del depósito, los pedidos preparados pasan a `enviado` con la hora real del evento.
- Historial de entregas (status `entregado`).

## Configuración rápida
1. Abrí esta carpeta `android` en Android Studio.
2. En `android/gradle.properties` reemplazá `MAPS_API_KEY` por tu key restringida de Google Maps.
3. Sincronizá el proyecto (Gradle Sync).
4. Corré en un dispositivo físico con GPS.

## Backend
- URL: `https://backend-0lcs.onrender.com/`
- Login: `POST /admin/auth/token` (form-urlencoded `username`, `password`).
- Pedidos: `GET /admin/orders` (Bearer token).
- Marcar entregado: `PATCH /orders/{id}/status` (Bearer token, JSON `{ "status": "entregado" }`).
- Ubicación: `POST /admin/driver-location` (Bearer token, JSON `{ lat, lon, ... }`).

## Coordenadas del depósito
- Lat: `-32.819094`
- Lon: `-68.804286`

## Notas
- Si la app muestra "usuario no es repartidor", el rol del usuario en el admin debe ser `repartidor`.
- El mapa y el tracking requieren permisos de ubicación en el dispositivo.
