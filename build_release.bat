@echo off
rem ======================================================================
rem  Dong goi Flow Batch Studio thanh bo cai 1-click:
rem    dist\FlowBatchStudio-Setup-<ver>.exe  (Inno Setup)
rem    C:\fbs-build\image\FlowBatchStudio\   (ban chay ngay, khong can cai; doi qua bien AFM_BUILD_DIR)
rem  Can tren may build: JDK 17+ (jlink, jpackage), Maven, Node.js, Python 3.13 (lenh py -3.13),
rem  Inno Setup 6.3+ (tuy chon: thieu thi chi tao ban chay ngay).
rem ======================================================================
setlocal EnableExtensions
chcp 65001 >nul
set "ROOT=%~dp0"
rem jlink/jpackage doc tham so theo bang ma ANSI: duong dan co dau (vd "Du an") bi hong thanh "?".
rem Vi vay build trong thu muc tam CHI GOM KY TU ASCII, roi chep ket qua ve %ROOT%dist.
if not defined AFM_BUILD_DIR set "AFM_BUILD_DIR=%SystemDrive%\fbs-build"
set "BUILD=%AFM_BUILD_DIR%"
set "IMAGE=%BUILD%\image\FlowBatchStudio"
set "DIST=%ROOT%dist"
set "APP_VERSION=1.0.0"
set "PY_VER=3.13.2"
set "PY_TAG=313"

rem --- Cong cu (doi qua bien moi truong neu can) ---
if not defined JAVA_HOME if exist "D:\Dev\Java\bin\jlink.exe" set "JAVA_HOME=D:\Dev\Java"
if not defined JAVA_HOME ( echo [LOI] Chua dat JAVA_HOME tro toi JDK 17+ & exit /b 1 )
if not exist "%JAVA_HOME%\bin\jpackage.exe" ( echo [LOI] %JAVA_HOME% khong phai JDK day du: thieu jpackage & exit /b 1 )
rem Maven Wrapper di kem du an: khong can cai Maven (lan dau tu tai Maven ve %USERPROFILE%\.m2)
if not defined MVN set "MVN=%ROOT%backend\mvnw.cmd"
if not defined ISCC set "ISCC=%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe"
rem Inno Setup cai theo kieu "chi cho nguoi dung hien tai" (winget --scope user) nam o %LOCALAPPDATA%\Programs
if not exist "%ISCC%" if exist "%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe" set "ISCC=%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe"
rem Duong dan du an co dau (Du an) lam Maven loi ma hoa neu khong ep UTF-8
set "MAVEN_OPTS=-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8"

if exist "%BUILD%" rmdir /s /q "%BUILD%"
mkdir "%BUILD%\jar" || goto :fail

echo.
echo [1/7] Frontend (Vue 3) -^> backend\src\main\resources\static
pushd "%ROOT%frontend" || goto :fail
if not exist node_modules ( call npm install --no-audit --no-fund || goto :fail_pop )
call npm run build || goto :fail_pop
popd

echo.
echo [2/7] Backend (Spring Boot) -^> app.jar
pushd "%ROOT%backend" || goto :fail
call "%MVN%" -q -B clean package -DskipTests || goto :fail_pop
popd
copy /y "%ROOT%backend\target\app.jar" "%BUILD%\jar\" >nul || goto :fail

echo.
echo [3/7] JRE thu gon (jlink)
"%JAVA_HOME%\bin\jlink" --add-modules java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.sql,java.xml,jdk.crypto.ec,jdk.unsupported,jdk.zipfs,jdk.localedata ^
  --include-locales=en,vi --strip-debug --no-man-pages --no-header-files --compress=2 --output "%BUILD%\runtime" || goto :fail

echo.
echo [4/7] App image + FlowBatchStudio.exe (jpackage)
set "ICON_ARG="
if exist "%ROOT%packaging\app.ico" (
  copy /y "%ROOT%packaging\app.ico" "%BUILD%\app.ico" >nul
  set ICON_ARG=--icon "%BUILD%\app.ico"
)
"%JAVA_HOME%\bin\jpackage" --type app-image --name FlowBatchStudio --app-version %APP_VERSION% ^
  --input "%BUILD%\jar" --main-jar app.jar --runtime-image "%BUILD%\runtime" --dest "%BUILD%\image" %ICON_ARG% ^
  --java-options "-Xmx1g" --java-options "-Dfile.encoding=UTF-8" || goto :fail

echo.
echo [5/7] Python embeddable %PY_VER% + thu vien
curl --ssl-no-revoke -fL -o "%BUILD%\python-embed.zip" "https://www.python.org/ftp/python/%PY_VER%/python-%PY_VER%-embed-amd64.zip" || goto :fail
powershell -NoProfile -Command "Expand-Archive -LiteralPath '%BUILD%\python-embed.zip' -DestinationPath '%IMAGE%\python' -Force" || goto :fail
copy /y "%ROOT%packaging\python%PY_TAG%._pth" "%IMAGE%\python\python%PY_TAG%._pth" >nul || goto :fail
py -%PY_VER:~0,4% -m pip install --disable-pip-version-check --only-binary=:all: --target "%IMAGE%\python\Lib\site-packages" -r "%ROOT%engine\requirements-runtime.txt" || goto :fail

echo.
echo [6/7] Engine (worker Python)
robocopy "%ROOT%engine" "%IMAGE%\engine" /e /xd __pycache__ /njh /njs /nfl /ndl >nul
if errorlevel 8 goto :fail
"%IMAGE%\python\python.exe" -c "import sys; sys.path.insert(0, r'%IMAGE%\engine'); import playwright.sync_api, requests, websockets, worker_main; print('python embeddable: OK')" || goto :fail

echo.
echo [7/7] Bo cai (Inno Setup) -^> %DIST%
rem Ban chay ngay (khong can cai) nam o %IMAGE%. Chi chep no toi thu muc CHI GOM KY TU ASCII:
rem launcher cua jpackage (JDK 17) khong chay duoc trong duong dan co dau.
if not exist "%ISCC%" (
  echo [BO QUA] Khong thay Inno Setup tai "%ISCC%".
  echo          Ban chay ngay ^(khong can cai^): %IMAGE%\FlowBatchStudio.exe
  echo          Cai Inno Setup 6.3+ ^(https://jrsoftware.org/isdl.php^) roi chay lai de tao file Setup.exe.
  exit /b 0
)
if not exist "%DIST%" mkdir "%DIST%"
copy /y "%ROOT%packaging\FlowBatchStudio.iss" "%BUILD%\FlowBatchStudio.iss" >nul || goto :fail
"%ISCC%" /Q /DAppVersion=%APP_VERSION% /DImageDir="%IMAGE%" /DIconFile="%BUILD%\app.ico" /O"%DIST%" "%BUILD%\FlowBatchStudio.iss" || goto :fail

echo.
echo ======================================================================
echo  XONG: %DIST%\FlowBatchStudio-Setup-%APP_VERSION%.exe
echo        Ban chay ngay ^(khong can cai^): %IMAGE%\FlowBatchStudio.exe
echo ======================================================================
exit /b 0

:fail_pop
popd
:fail
echo.
echo [LOI] Buoc tren that bai. Xem thong bao phia tren.
exit /b 1
