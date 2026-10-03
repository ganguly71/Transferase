@echo off
echo Starting Web Transfer Server and Client...

start "Web Transfer - Backend" cmd /k "cd /d "%~dp0server" && node index.js"
start "Web Transfer - Frontend" cmd /k "cd /d "%~dp0client" && npm run dev -- --host"