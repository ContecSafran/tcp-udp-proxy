# 배포용 Windows 실행 파일(exe) 빌드 스크립트
# 사용법: powershell -ExecutionPolicy Bypass -File build-exe.ps1
$ErrorActionPreference = "Stop"

$JdkHome  = "C:\Program Files\Java\jdk-21"
$AppName  = "TcpUdpProxy"
$AppVer   = "1.0"
$MainJar  = "tcp-udp-proxy.jar"
$MainCls  = "com.proxy.ProxyApp"

if (-not (Test-Path $JdkHome)) { throw "JDK를 찾을 수 없습니다: $JdkHome" }

Write-Host "[1/4] Maven 패키지 빌드"
$env:JAVA_HOME = $JdkHome
mvn -q -DskipTests clean package
if ($LASTEXITCODE -ne 0) { throw "Maven 빌드 실패" }

Write-Host "[2/4] 스테이징"
Remove-Item -Recurse -Force dist, build-stage -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force build-stage\input | Out-Null
Copy-Item "target\tcp-udp-proxy-$AppVer-SNAPSHOT-jar-with-dependencies.jar" "build-stage\input\$MainJar"

Write-Host "[3/4] 축소 런타임 생성 (jlink)"
& "$JdkHome\bin\jlink" --add-modules java.base,java.desktop,java.logging,java.naming `
  --strip-debug --no-header-files --no-man-pages --compress=zip-6 `
  --output build-stage\runtime
if ($LASTEXITCODE -ne 0) { throw "jlink 실패" }

Write-Host "[4/4] 실행 파일 생성 (jpackage)"
& "$JdkHome\bin\jpackage" --type app-image `
  --name $AppName --app-version $AppVer --vendor "Contec" --description "TCP/UDP Proxy" `
  --input build-stage\input --main-jar $MainJar --main-class $MainCls `
  --runtime-image build-stage\runtime --dest dist `
  --java-options "-Dfile.encoding=UTF-8" --java-options "-Xmx512m"
if ($LASTEXITCODE -ne 0) { throw "jpackage 실패" }

Compress-Archive -Path "dist\$AppName" -DestinationPath "dist\$AppName-$AppVer-win-x64.zip" -Force

Write-Host ""
Write-Host "완료: dist\$AppName\$AppName.exe"
Write-Host "배포용: dist\$AppName-$AppVer-win-x64.zip"
