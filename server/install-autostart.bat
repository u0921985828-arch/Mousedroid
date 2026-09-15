@echo off
setlocal
set "DIR=%~dp0"
set "DIR=%DIR:~0,-1%"
set "STARTUP=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup"
set "VBS=%STARTUP%\usbmouse.vbs"

if exist "%DIR%\UsbMouse.exe" (
  set "CMD=""%DIR%\UsbMouse.exe"" --auto --log"
  echo Usando el .exe portatil.
) else (
  echo No hay UsbMouse.exe, uso Python. ^(Ejecuta build-exe.bat para no depender de el.^)
  python -m pip install --quiet --disable-pip-version-check pynput
  if errorlevel 1 (
    echo [!] Ni .exe ni Python. Ejecuta build-exe.bat en un PC con Python.
    pause
    exit /b 1
  )
  set "CMD=pythonw.exe server.py --auto --log"
)

> "%VBS%" echo Set s = CreateObject("WScript.Shell")
>> "%VBS%" echo s.CurrentDirectory = "%DIR%"
>> "%VBS%" echo s.Run "%CMD%", 0, False

echo.
echo Arrancara solo con Windows, sin ventana.
echo   Script:  %VBS%
echo   Log:     %DIR%\usbmouse.log
echo.
wscript "%VBS%"
echo Lanzado. Enchufa el movil.
pause
