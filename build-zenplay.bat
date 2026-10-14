@echo off
rem ZenPlay Universal - build the two public APKs with Android Studio's bundled Java.
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
cd /d "%~dp0"
echo Compilando ZenPlay... (puede tardar varios minutos)
if exist ..\ZenPlayTV-update.zip (
  tar -xf ..\ZenPlayTV-update.zip -C ..
  del ..\ZenPlayTV-update.zip
)
del build-result.txt 2>nul
del ZenPlay.apk 2>nul
del ZenPlayLegacy.apk 2>nul
call gradlew.bat :app:assembleNormalRelease :app:assembleLegacyRelease --stacktrace > build-zenplay.log 2>&1
if exist app\build\outputs\apk\normal\release\app-normal-release.apk if exist app\build\outputs\apk\legacy\release\app-legacy-release.apk (
  copy /y app\build\outputs\apk\normal\release\app-normal-release.apk ZenPlay.apk
  copy /y app\build\outputs\apk\legacy\release\app-legacy-release.apk ZenPlayLegacy.apk
  echo LISTO > build-result.txt
) else (
  echo ERROR > build-result.txt
)
