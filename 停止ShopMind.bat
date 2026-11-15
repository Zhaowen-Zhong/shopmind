@echo off
chcp 65001 >nul
title 停止 ShopMind
echo 正在停止 ShopMind 服务...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -like '*shopmind-web-1.0.0.jar*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force; Write-Host ('已结束进程 PID=' + $_.ProcessId) }"
echo.
echo ShopMind 已停止。
pause
