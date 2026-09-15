@echo off
setlocal
set "VBS=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\usbmouse.vbs"
if exist "%VBS%" (
  del "%VBS%"
  echo Arranque automatico desinstalado.
) else (
  echo No estaba instalado.
)
taskkill /f /im pythonw.exe >nul 2>&1
pause
