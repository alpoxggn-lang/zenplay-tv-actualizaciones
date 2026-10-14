@echo off
rem ZenPlay TV - build the release APK with Android Studios bundled Java.
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
cd /d "%~dp0"
echo Compilando ZenPlay... (puede tardar varios minutos)
if exist ..\ZenPlayTV-update.zip (
  tar -xf ..\ZenPlayTV-update.zip -C ..
  del ..\ZenPlayTV-update.zip
)
del build-result.txt 2>nul
del ZenPlay-TV.apk 2>nul
call gradlew.bat :app:assembleStandardRelease --stacktrace > build-zenplay.log 2>&1
if exist app\build\outputs\apk\standard\release\app-standard-release.apk (
  copy /y app\build\outputs\apk\standard\release\app-standard-release.apk ZenPlay-TV.apk
  echo LISTO > build-result.txt
) else (
  echo ERROR > build-result.txt
)
