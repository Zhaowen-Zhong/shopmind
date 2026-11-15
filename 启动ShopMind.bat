@echo off
chcp 65001 >nul
cd /d "%~dp0"
title ShopMind 启动器

set "JAR=shopmind-web\target\shopmind-web-1.0.0.jar"

if not exist "%JAR%" (
    echo [1/3] 首次运行，正在构建工程，约需 1-3 分钟...
    call mvnw.cmd package -DskipTests
    if errorlevel 1 (
        echo 构建失败，请检查 JDK 是否安装、网络是否正常后重试。
        pause
        exit /b 1
    )
) else (
    echo [1/3] 已检测到可执行程序，跳过构建。
)

echo [2/3] 正在启动 ShopMind 服务，含 10 万条索引重建，约需 3-5 分钟...
start "ShopMind 服务" /min java -jar "%JAR%"

echo [3/3] 等待服务就绪，就绪后将自动打开浏览器...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ok=$false; for($i=0;$i -lt 150;$i++){ try { Invoke-WebRequest -Uri 'http://localhost:8080/api/stats' -UseBasicParsing -TimeoutSec 2 | Out-Null; $ok=$true; break } catch { Start-Sleep -Seconds 2 } }; if($ok){ Start-Process 'http://localhost:8080' } else { Write-Host '服务在 5 分钟内未就绪，请查看 ShopMind 服务窗口中的日志' }"

echo.
echo 浏览器已打开。本窗口可关闭；停止服务请双击 停止ShopMind.bat
pause
