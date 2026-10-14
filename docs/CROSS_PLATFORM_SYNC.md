# Sincronización ZenPlay entre plataformas

Fecha de diseño: 2026-10-13

## Estado

Este documento define el contrato objetivo. La sincronización por Internet entre Android,
Windows y macOS **todavía no está implementada**. Android ya dispone de un modelo local avanzado y
de sincronización cifrada en la misma red; escritorio ya persiste favoritos, historial y progreso.
El trabajo pendiente es añadir identidad común, perfiles persistentes en escritorio, outbox/cursor y
un servicio ZenPlay seguro.

## Primera entrega

La primera versión sincroniza únicamente:

- perfiles y su identidad estable;
- favoritos de canales, películas y series;
- historial de reproducción;
- progreso de películas y episodios;
- episodios vistos.

Quedan fuera de la primera versión: archivos descargados, grabaciones, credenciales del proveedor,
PIN parental, avatares binarios, cachés, ajustes específicos del dispositivo y diagnósticos.

## Reglas de seguridad

1. Todo el tráfico utiliza TLS.
2. La contraseña de ZenPlay se utiliza transitoriamente para validar la cuenta y no se persiste en el
   backend, en registros ni en eventos de sincronización.
3. El backend identifica la cuenta con un HMAC calculado con un secreto exclusivo del servidor.
4. El cliente recibe un access token de vida corta y un refresh token rotatorio. En base de datos se
   conserva únicamente el hash del refresh token.
5. Cada instalación posee un `deviceId` aleatorio revocable. No se deriva de IMEI, Android ID, MAC ni
   de otro identificador de hardware.
6. Windows protege secretos locales mediante `safeStorage`/DPAPI y macOS mediante Keychain. Android
   utiliza una llave AES-GCM no exportable de Android Keystore.
7. URLs completas, usuario, contraseña y tokens se redactan en registros y diagnósticos.

## Identidad estable

- Cuenta: HMAC del identificador normalizado de cuenta, solo calculado por el backend.
- Perfil: UUID permanente de sincronización; los IDs autoincrementales permanecen locales.
- Canal/película/serie: versión de esquema + tipo + identificador remoto Xtream.
- Episodio: identificador remoto de serie + temporada + episodio; el ID remoto del episodio se
  conserva como alias cuando está disponible.
- Evento: UUID generado por el dispositivo para que cada `push` sea idempotente.

Nunca se utiliza un ID de Room/SQLite como clave de nube.

## API mínima v1

### Sesión

- `POST /v1/session/exchange`: valida la cuenta y entrega tokens.
- `POST /v1/session/refresh`: rota el refresh token y entrega un access token nuevo.
- `POST /v1/devices/revoke`: invalida otro dispositivo de la cuenta.

### Sincronización

- `POST /v1/sync/bootstrap`: adopta o descarga el primer estado de una cuenta.
- `POST /v1/sync/push`: recibe un lote idempotente del outbox.
- `GET /v1/sync/pull?cursor=<cursor>`: devuelve cambios posteriores al cursor confirmado.
- `POST /v1/sync/ack`: confirma el cursor aplicado por el dispositivo.

Cada mutación incluye como mínimo:

```text
eventId, deviceId, profileUuid, schemaVersion, entityType,
contentKey, operation, modifiedAt, logicalClock, payload
```

El servidor limita tamaño y cantidad de eventos por lote. Campos desconocidos se ignoran solo cuando
la versión del contrato declara que es seguro hacerlo; una versión incompatible se rechaza de forma
explícita.

## Funcionamiento sin conexión

La escritura visible para el usuario y la inserción en el outbox ocurren en la misma transacción
local. La interfaz nunca espera una respuesta del backend.

- Android procesa el outbox con WorkManager al recuperar red, al entrar a primer plano y de forma
  periódica.
- Windows y macOS lo procesan al iniciar, recuperar red, volver a primer plano y mediante un
  temporizador moderado.
- Los reintentos utilizan backoff exponencial con jitter y respetan `Retry-After`.
- Un `push` confirmado elimina solo los eventos incluidos en ese acuse.
- Un `pull` se aplica por lote en una transacción; el cursor avanza únicamente si todo el lote queda
  guardado.

## Conflictos

- Favoritos: gana la modificación más reciente; eliminar crea un tombstone.
- Historial: gana el evento de reproducción más reciente.
- Progreso: gana la actualización más reciente, no la posición numéricamente mayor. El usuario puede
  volver a empezar o retroceder legítimamente.
- Episodio visto: se conserva la marca más reciente; una acción explícita de “marcar como no visto”
  produce un evento y un tombstone equivalentes.
- Empates: reloj lógico y después `deviceId`, en orden determinista.

Los tombstones se conservan hasta que todos los dispositivos activos hayan confirmado un cursor que
los incluya, más un periodo de seguridad. Revocar o expirar un dispositivo evita que bloquee la
limpieza indefinidamente.

## Migración sin pérdida de datos

### Android

1. Añadir `syncProfileUuid`, cursor y outbox sin sustituir tablas existentes.
2. Asignar UUID a cada perfil dentro de una migración transaccional.
3. Convertir favoritos, historial y progreso a sus claves estables actuales.
4. Conservar el sincronizador local por Wi-Fi; nube y LAN comparten el mismo resolvedor, no dos
   políticas de conflicto.

### Windows y macOS

1. Crear una tabla real de perfiles e importar los perfiles que hoy viven en `localStorage`.
2. Añadir perfil, clave estable, fecha de modificación y tombstones a favoritos, historial y
   progreso.
3. Asociar datos antiguos únicamente cuando el perfil sea inequívoco; ante nombres duplicados se
   solicita una elección.
4. Mantener las columnas/tablas anteriores durante al menos una versión para permitir reversión.

### Primer enlace

El primer dispositivo de una cuenta sube un snapshot. Un dispositivo posterior descarga antes de
subir y combina con las mismas reglas de conflicto. El proceso puede repetirse de forma idempotente
si la app se cierra o pierde conexión.

## Criterios de aceptación

1. Iniciar una película en móvil, detenerla y continuar en TV, Windows o macOS desde la misma
   posición razonable.
2. Marcar/desmarcar un favorito sin conexión y converger al recuperar red.
3. Una eliminación no reaparece después de sincronizar un dispositivo que estuvo desconectado.
4. Dos dispositivos que modifican el mismo progreso terminan con el mismo resultado determinista.
5. Repetir `push`, `pull` o `bootstrap` no duplica datos.
6. Ningún payload, registro o fila del backend contiene la contraseña del proveedor.
7. Revocar un dispositivo invalida su refresh token sin cerrar los demás.
8. Actualizar una instalación con datos existentes no borra perfiles, favoritos, historial ni
   progreso.
9. Un backend temporalmente caído no bloquea reproducción ni navegación local.

## Orden de implementación

1. Contrato, migraciones e identidades comunes.
2. Servicio de autenticación, dispositivos y tokens.
3. Outbox/cursor de Android.
4. Perfiles persistentes y outbox de Windows/macOS.
5. Favoritos, historial y progreso de extremo a extremo.
6. Pruebas multi-dispositivo, desconexión, conflicto, actualización y recuperación.
7. Solo después: restricciones parentales y otros datos de perfil autorizados.
