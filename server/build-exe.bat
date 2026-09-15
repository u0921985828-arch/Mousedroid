@echo off
cd /d "%~dp0"
echo Construyendo UsbMouse.exe (solo hace falta una vez)...
python -m pip install --quiet --disable-pip-version-check pyinstaller pynput
if errorlevel 1 (
  echo [!] Necesitas Python 3 solo para ESTE paso. Luego ya no hace falta.
  pause
  exit /b 1
)
python -m PyInstaller --onefile --noconsole --name UsbMouse ^
  --distpath "%~dp0" --workpath "%~dp0build" --specpath "%~dp0build" server.py
rmdir /s /q "%~dp0build" 2>nul
echo.
if exist "%~dp0UsbMouse.exe" (
  echo Listo: UsbMouse.exe  ^(portatil, no necesita Python^)
  echo Copialo donde quieras y ejecuta install-autostart.bat
) else (
  echo [!] Algo fallo en la compilacion.
)
pause
