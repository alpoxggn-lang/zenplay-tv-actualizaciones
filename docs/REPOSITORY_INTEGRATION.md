# ZenPlay Universal — estrategia segura de integración

Fecha: 2026-10-13

## Propósito

Este documento define qué se conservará de ZenPlay, qué ideas se tomarán de los cinco proyectos
evaluados y cómo se evitará mezclar código con licencias incompatibles. La aplicación no se
reescribe desde cero: Android parte de la base existente de ZenPlay/OwnTV y escritorio conserva la
base actual de ZenPlay Desktop/IPTVnator.

La revisión de licencias es una guía técnica de cumplimiento, no asesoría jurídica. Antes de una
distribución comercial definitiva se debe conservar evidencia de procedencia y, si es necesario,
obtener una revisión legal.

## Principios obligatorios

1. Conservar el comportamiento estable de ZenPlay antes de sustituir componentes.
2. Integrar funciones por etapas, con pruebas de actualización y reproducción después de cada una.
3. No copiar código, recursos, nombres, logotipos ni diseños de un proyecto cuando su licencia o
   procedencia no lo permita.
4. Tratar una función observable como referencia de producto, no como permiso para copiar su
   implementación.
5. Mantener separados los árboles Android y escritorio cuando sus obligaciones de licencia sean
   diferentes.
6. Registrar en cada incorporación el repositorio, la revisión, la licencia, los archivos usados y
   las modificaciones realizadas.
7. Utilizar únicamente la identidad visual y los recursos autorizados de ZenPlay.

## Matriz de repositorios

| Proyecto | Licencia observada | Uso previsto | Restricción práctica |
|---|---|---|---|
| OwnTV / OwnTV Core | GPL-3.0 | Base Android ya existente: Xtream, catálogo, navegación TV y reproducción | La distribución derivada debe cumplir GPL, conservar avisos y ofrecer el código fuente correspondiente |
| Xtream M3U Plus IPTV Player | GPL-3.0 | Referencia para validación Xtream, variantes de URL, caché, historial y recuperación | No copiar módulos Python/PyQt/VLC al escritorio MIT ni trasladarlos literalmente a Android |
| IPTVnator | MIT | Conservar la base de escritorio que ya utiliza ZenPlay y sus componentes permitidos | Mantener aviso de copyright y licencia; su marca y recursos gráficos no quedan concedidos por la licencia de código |
| Extreme-InfiniTV | GPL-3.0-or-later | Referencia para interfaz adaptable, listas grandes, diagnóstico y reparación de audio | Reimplementar de forma independiente salvo que el componente receptor también se distribuya bajo términos compatibles |
| ynoTV | AGPL-3.0 | Referencia para salud de streams, cambio de fuente, DVR, multiview y PiP | No copiar código en ZenPlay propietario/MIT; AGPL puede exigir ofrecer el fuente también en uso por red |

## Revisión adicional para acelerar el desarrollo

El 2026-10-13 se fijaron copias locales de solo lectura de otros proyectos activos. Esta segunda
revisión no sustituye la base de ZenPlay ni autoriza una mezcla masiva: sirve para localizar
soluciones pequeñas, comprobables y con procedencia clara.

| Proyecto y revisión | Licencia | Decisión | Aporte que se evaluará |
|---|---|---|---|
| `isnow-git/strix` `a131b43` | Apache-2.0 | Adaptar selectivamente | Perfiles de búfer/ABR para TV económica, caché Coil proporcional a memoria, reintento con backoff, circuit breaker y pruebas de paginación |
| `SERFF/iptv-app` `4705227` | Apache-2.0 | Adaptar selectivamente | Diagnóstico de capacidades de audio/HDMI, flags seguros para MPEG-TS, selección explícita de pistas y programación única con WorkManager |
| `jonzey231/AerioTV-Android` `900e767` | GPL-3.0-or-later | Compatible solo con la línea Android GPL; integrar con atribución o usar como referencia | Interfaz adaptativa en una sola base, presión de memoria, PiP/multiview y fallback de audio FFmpeg con procedencia reproducible |
| `Guts444/Onyx` `b321279` | MIT | Reutilizable en escritorio con aviso MIT | Empaquetado reproducible de libmpv, verificación por hash, reproducción nativa, caché local y controles de integridad para Windows |
| `air-tv/iptv` `2f6303f` | MIT | Evaluar como biblioteca o portar pruebas acotadas | Parser Xtream/M3U/XMLTV por streaming, límites de memoria, snapshots atómicos, redacción de credenciales e identificadores estables |

La base actual ya implementa Media3 con fallback de decodificador, selección de idiomas, paginación
Room/Paging 3, caché Coil y perfiles de búfer. Por eso no se copiarán versiones duplicadas de esas
piezas. Cada candidato se comparará mediante una prueba que demuestre una carencia concreta de
ZenPlay; si la prueba ya pasa, se conserva la implementación existente.

Se descartó el uso directo de proyectos sin una licencia expresa, aunque el repositorio sea público.
Los repositorios jóvenes se consideran una fuente de pruebas e ideas, no una garantía de calidad.

## Qué se conserva de ZenPlay Android

- Conexión y catálogo Xtream de la marca ZenPlay.
- Base local, caché, historial, favoritos y progreso existentes, con migraciones compatibles.
- EPG, TV en vivo, películas, series, perfiles, PiP, multiview, DVR y descargas permitidas que ya
  funcionen correctamente.
- Navegación D-pad de ZenPlay TV y navegación táctil de ZenPlay Mobile.
- Firma de producción y el identificador estable `tv.zenplay.universal` para la nueva línea
  Universal; este paquete no sustituye las apps TV/móvil anteriores.
- Flujos probados de actualización y recuperación ante errores.

La integración móvil/TV se hará mediante presentaciones adaptativas sobre servicios y modelos
compartidos. No se duplicará el catálogo ni se mantendrán dos lógicas de negocio distintas.

## Reproductor Android

La cadena prevista es:

1. **AndroidX Media3 ExoPlayer**, motor principal para TV en vivo, VOD y episodios.
2. Reintento controlado con ajustes compatibles de contenedor, tipo MIME, búfer y fuente.
3. **mpv/libmpv**, motor nativo alternativo cuando Media3 confirme incompatibilidad de códec,
   contenedor, pista de audio o stream.
4. Reproductor externo, únicamente como opción explícita del usuario o último recurso.

Media3 debe conservar selección de audio, subtítulos, calidad, progreso, PiP y control del ciclo de
vida. El salto a mpv no se realizará por una interrupción breve de red: primero se agotarán reintentos
acotados para evitar reinicios o cambios de motor innecesarios. El diagnóstico registrará el motor,
la fuente, el tipo de fallo y el resultado, sin guardar credenciales ni URLs sensibles completas.

El soporte mpv se añadirá mediante una dependencia cuya licencia, binarios nativos, arquitecturas y
avisos sean auditados antes de publicar. FFmpeg y demás códecs se evaluarán también por su licencia y
configuración de compilación; no se incorporarán binarios de origen desconocido.

## Qué se conserva de ZenPlay Desktop

- La interfaz y la organización ya construidas sobre la base permitida de IPTVnator.
- Persistencia SQLite, trabajadores en segundo plano y la integración nativa con el sistema.
- Inicio de sesión ZenPlay, catálogo, historial y progreso existentes cuando sean compatibles con el
  nuevo servicio de sincronización.
- Empaquetado separado para Windows y macOS.

La interfaz puede continuar usando tecnología web para dibujar ventanas, pero la reproducción y el
trabajo pesado no dependerán del reproductor multimedia del navegador.

## Reproductor de Windows y macOS

La cadena prevista es:

1. **MPV nativo integrado**, motor principal, ejecutado fuera del proceso de interfaz y controlado
   mediante IPC.
2. Decodificación por hardware cuando el equipo y el códec la admitan, con retorno seguro a
   decodificación por software.
3. Reintento y normalización local con herramientas nativas auditadas cuando falle el contenedor o
   una pista de audio.
4. Motor nativo alternativo o apertura externa opcional si MPV no puede reproducir la fuente.
5. HTML5 solo como último recurso de compatibilidad o diagnóstico, nunca como experiencia normal.

La lectura del catálogo, caché de imágenes, SQLite, análisis de medios y operaciones de red se
mantendrán fuera del hilo de la interfaz. Esto permite usar CPU, GPU, memoria, almacenamiento y
decodificadores del equipo sin congelar la navegación. La aplicación respetará límites de memoria y
cancelará tareas que ya no sean visibles.

No se trasladará código GPL o AGPL de los otros reproductores al árbol MIT de escritorio. Las ideas
de tolerancia a fallos, diagnóstico o multiview se implementarán con código propio o dependencias
compatibles y documentadas.

## Funciones que se reimplementarán de forma independiente

- Detección de capacidades y diseño adaptable por tamaño de ventana, entrada y memoria disponible.
- Virtualización, paginación y carga progresiva para catálogos grandes.
- Estado de salud del stream, reintentos acotados y cambio automático de motor.
- Diagnóstico de códec, contenedor, audio, subtítulos y red con información privada redactada.
- Multiview con reducción automática según hardware y conexiones autorizadas.
- PiP, cambio rápido de canal, EPG, catch-up y DVR cuando el proveedor y el dispositivo lo permitan.
- Sincronización ZenPlay de perfiles, favoritos, historial y progreso entre Android, Windows y macOS.
- Resolución de conflictos y trabajo sin conexión mediante identificadores estables de contenido.

Estas implementaciones podrán reproducir el comportamiento útil observado, pero no copiarán nombres
internos, estructura, algoritmos expresivos, pruebas, textos ni recursos de los proyectos de
referencia.

## Elementos que no se copiarán

- Código GPL/AGPL dentro del cliente de escritorio MIT o de un servicio propietario.
- Fragmentos o componentes sin licencia claramente identificada.
- Logos, nombres, capturas, iconos, fuentes o material promocional de los cinco proyectos.
- Diseños completos de Netflix, Prime Video, HBO Max, Disney+ o Apple TV.
- Servidores, credenciales, listas, contenido o datos de demostración de terceros.
- Binarios precompilados de mpv, FFmpeg, VLC u otros motores sin procedencia y configuración
  verificables.
- Implementaciones de DRM, evasión de restricciones, límites de conexión o acceso no autorizado.

## Obligaciones de publicación

### Android

Mientras ZenPlay Universal siga siendo una obra derivada de OwnTV/OwnTV Core bajo GPL-3.0, cada
versión distribuida debe acompañarse de los avisos y la oferta o acceso al código fuente
correspondiente, incluyendo los cambios necesarios para compilarla, conforme a la licencia. Cambiar
el nombre o la interfaz no elimina esta obligación.

Si en el futuro se desea un Android cerrado, no basta con mover o renombrar archivos: será necesario
obtener otra licencia de los titulares o sustituir el código derivado mediante una implementación
independiente verificable.

### Windows y macOS

La base MIT de IPTVnator permite distribución comercial y modificaciones conservando su aviso de
copyright y texto de licencia. Las dependencias y el empaquetado de MPV/FFmpeg deben revisarse por
separado porque su cumplimiento depende de las opciones concretas de compilación y distribución.

### Backend de sincronización

El backend propio no recibirá código AGPL/GPL copiado. Se diseñará como un servicio independiente y
solo intercambiará datos mediante una API documentada. No almacenará contraseñas Xtream en texto
plano ni expondrá URLs firmadas o credenciales en registros.

## Flujo de aceptación de una incorporación

Antes de integrar cualquier componente externo:

1. Fijar repositorio y revisión exacta.
2. Leer la licencia raíz y las licencias de dependencias y recursos.
3. Clasificarlo como código reutilizable, dependencia, referencia funcional o descartado.
4. Documentar procedencia y obligaciones.
5. Implementar en una rama aislada con pruebas.
6. Verificar Android Normal y Legacy, o Windows y macOS según corresponda.
7. Probar actualización, reproducción, audio, subtítulos, control remoto/táctil y consumo de
   recursos.
8. Incorporar avisos y fuentes exigidos antes de generar una versión pública.

## Decisión resumida

- **Android:** evolucionar la base ZenPlay/OwnTV; Media3 primero y mpv como fallback nativo.
- **Escritorio:** evolucionar ZenPlay Desktop/IPTVnator; MPV nativo primero, sin depender del
  reproductor del navegador.
- **Otros tres repositorios:** aprovechar patrones y requisitos funcionales; reimplementar con código
  propio cuando la licencia no sea compatible.
- **Sincronización:** servicio ZenPlay común, independiente y seguro para Android, Windows y macOS.
- **Marca:** usar exclusivamente identidad visual, textos y recursos autorizados de ZenPlay.
