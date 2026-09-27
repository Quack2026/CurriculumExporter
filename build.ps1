# 一键编译「课表导出.exe」
#   用法: powershell -ExecutionPolicy Bypass -File build.ps1
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$csc  = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
$out  = Join-Path (Split-Path -Parent $root) '课表导出.exe'

if (-not (Test-Path $csc)) {
    throw "找不到 C# 编译器: $csc  (需要 .NET Framework 4.x,Win10/11 自带)"
}

Write-Host "编译器: $csc"
Write-Host "源码  : $root\src"
Write-Host "产物  : $out"
Write-Host ""

& $csc /nologo /target:winexe `
    "/win32manifest:$root\src\app.manifest" `
    "/win32icon:$root\src\app.ico" `
    /codepage:65001 /optimize+ `
    "/out:$out" `
    /r:System.dll /r:System.Core.dll /r:System.Drawing.dll /r:System.Windows.Forms.dll /r:System.Web.Extensions.dll `
    "$root\src\Program.cs" "$root\src\Network.cs" "$root\src\IcsBuilder.cs"

if ($LASTEXITCODE -ne 0) { throw "编译失败 (exit $LASTEXITCODE)" }

Write-Host ""
Write-Host "编译成功:" -ForegroundColor Green
Get-Item $out | ForEach-Object { Write-Host ("  {0}   {1:N1} KB   {2}" -f $_.Name, ($_.Length / 1KB), $_.LastWriteTime) }
Write-Host ""
Write-Host "SHA256:" -ForegroundColor Green
Get-FileHash $out -Algorithm SHA256 | ForEach-Object { Write-Host ("  " + $_.Hash) }