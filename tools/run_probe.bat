@echo off
cd /d "%~dp0"
title 云班课协议探针
echo ================================================
echo   云班课协议探针 - 只读取信息,不修改任何东西
echo   跑完会在同目录生成 probe_out.txt
echo   把这个文件整个发回来即可
echo ================================================
echo.
set "ACCOUNT="
set "PASSWORD="
set /p ACCOUNT=请输入云班课账号(学号/手机号):
if "%ACCOUNT%"=="" goto empty
set /p PASSWORD=请输入密码:
if "%PASSWORD%"=="" goto empty
echo.
echo ---- 开始,请稍等 ----
python "%~dp0ybk_probe.py" --account %ACCOUNT% --password %PASSWORD%
echo.
echo ---- 结束 ----
if exist "%~dp0probe_out.txt" explorer /select,"%~dp0probe_out.txt"
pause
exit /b 0

:empty
echo 账号或密码为空,退出。
pause
exit /b 1
