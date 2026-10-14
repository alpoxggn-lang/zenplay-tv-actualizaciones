# ZenPlay Universal — línea base

Fecha de inicio: 2026-10-13

## Alcance confirmado

- Un solo proyecto Android adaptable a Android TV, Google TV, Fire TV, teléfonos y tabletas.
- Exactamente dos APK de distribución:
  - `ZenPlay.apk` (Normal).
  - `ZenPlayLegacy.apk` (Legacy).
- Una misma cuenta, perfiles, favoritos, historial y progreso sincronizados con Windows y macOS.
- Windows y macOS mantienen instaladores propios; no forman parte de las dos APK.
- Fuente Xtream fija de la marca ZenPlay. La aplicación solo solicita usuario y contraseña.
- No incluye prueba de 24 horas ni una nueva aplicación web.
- Diseño plateado/gris con acentos azul y morado, adaptado por capacidades y tamaño de ventana.

## Orden confirmado de Inicio

1. Banner destacado.
2. Continuar viendo.
3. Películas agregadas.
4. Series actualizadas y agregadas.
5. TV en vivo.

## Navegación adaptable

- Android TV, Google TV y Fire TV muestran una sola navegación principal superior.
- Las columnas izquierdas de TV en vivo, Películas y Series son navegadores contextuales de
  categorías/contenido; nunca repiten el menú principal.
- Teléfonos usan una sola barra inferior.
- Tabletas usan barra inferior, rail o navegación superior según el ancho y la orientación, pero
  nunca dos menús principales simultáneos.

## Reutilización

La aplicación no se reescribe desde cero. La base inicial procede de ZenPlay TV, ZenPlay Mobile y
OwnTV Core. Se conservarán catálogo, protocolos, base local, reproductores, EPG, perfiles, favoritos,
historial, progreso, multiview, DVR, PiP, descargas permitidas y actualización. La interfaz táctil de
ZenPlay Mobile y la navegación D-pad de ZenPlay TV se integrarán como presentaciones adaptativas.

Los repositorios OwnTV, Xtream M3U Plus IPTV Player, IPTVnator, Extreme-InfiniTV y ynoTV se evaluarán
por función y licencia. Solo se reutilizará código cuando sea compatible; en los demás casos se usará
como referencia para una implementación propia.

## Línea base comprobada

- La copia original de ZenPlay Mobile compiló en su antiguo sabor `standardDebug`.
- La copia original de ZenPlay TV tenía dos imports incompatibles en `TopBar.kt`.
- La nueva base `ZenPlayUniversal` corrige esos imports y compila/prueba en `normalDebug` y
  `legacyDebug`.
- Legacy ya activa un perfil conservador: reduce efectos de vidrio y limita multiview a dos ventanas
  (una en equipos declarados de poca memoria). Su piso comprobado continúa siendo Android 8/API 26
  y Fire OS 7 hasta auditar y reconstruir Core, player-core y libmpv para una API menor.
- El actualizador distingue de forma exacta `ZenPlay.apk` y `ZenPlayLegacy.apk`; no selecciona el
  primer APK ARM encontrado.
- Las carpetas originales `ZenPlayTV` y `ZenPlayMobile` permanecen sin cambios.
- Los tres originales gráficos entregados por el propietario se conservan en `brand/source/`.

## Progreso estimado inicial

- Funcionalidad IPTV local existente: aproximadamente 87%.
- Objetivo completo Android + escritorio + sincronización: aproximadamente 65–70%.
- La mayor deuda se concentra en el host Android único, Legacy real, sincronización cloud, seguridad
  de credenciales, migración y pruebas sobre dispositivos reales.

Estas cifras son estimaciones de planificación basadas en auditoría estática y compilación, no
porcentajes de cobertura de pruebas.

