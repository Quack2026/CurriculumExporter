# 一键编译 curriculum-exporter.exe
#   用法: powershell -ExecutionPolicy Bypass -File build.ps1
#
# 为什么优先用 Roslyn 编译器(VS Build Tools 自带):
#   它支持 /deterministic,能产出【可复现】的二进制 ——
#   同一份源码反复编译得到同一个 SHA256,哈希才能用来证明"构建未被篡改"。
#   若机器上只有系统自带的旧 csc,仍能编译,但它会把编译时间写进 PE 头,
#   每次哈希都不同,这时哈希不能用于比对。
$ErrorActionPreference = 'Stop'

$root   = Split-Path -Parent $MyInvocation.MyCommand.Path
$srcDir = Join-Path $root 'src'
$out    = Join-Path $root 'curriculum-exporter.exe'   # 固定输出路径,是可复现的前提

function Find-Csc {
    $cands = New-Object System.Collections.ArrayList
    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
    if (Test-Path $vswhere) {
        $vs = & $vswhere -latest -products * -property installationPath 2>$null
        if ($vs) { [void]$cands.Add((Join-Path $vs 'MSBuild\Current\Bin\Roslyn\csc.exe')) }
    }
    [void]$cands.Add('C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\MSBuild\Current\Bin\Roslyn\csc.exe')
    [void]$cands.Add('C:\Program Files\Microsoft Visual Studio\2022\Community\MSBuild\Current\Bin\Roslyn\csc.exe')
    [void]$cands.Add((Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'))
    foreach ($c in $cands) { if ($c -and (Test-Path $c)) { return [string]$c } }
    throw '找不到 csc.exe(需要 .NET Framework 4.x 或 Visual Studio Build Tools)'
}

$csc = Find-Csc
$isRoslyn = $csc -like '*Roslyn*'

Write-Host ("编译器 : " + $csc)
if ($isRoslyn) { Write-Host "可复现 : 是 (/deterministic)" } else { Write-Host "可复现 : 否 (旧 csc,哈希每次会变)" }
Write-Host ("输出   : " + $out)
Write-Host ""

$a = New-Object System.Collections.ArrayList
[void]$a.AddRange([string[]]@('/nologo','/target:winexe','/codepage:65001','/optimize+'))
if ($isRoslyn) { [void]$a.Add('/deterministic') }
[void]$a.AddRange([string[]]@(
    ("/win32manifest:" + (Join-Path $srcDir 'app.manifest')),
    ("/win32icon:" + (Join-Path $srcDir 'app.ico')),
    ("/out:" + $out),
    '/r:System.dll','/r:System.Core.dll','/r:System.Drawing.dll','/r:System.Windows.Forms.dll','/r:System.Web.Extensions.dll',
    (Join-Path $srcDir 'Program.cs'), (Join-Path $srcDir 'Network.cs'), (Join-Path $srcDir 'IcsBuilder.cs')
))

& $csc @a
if ($LASTEXITCODE -ne 0) { throw "编译失败 (exit $LASTEXITCODE)" }

Write-Host ""
Write-Host "编译成功:" -ForegroundColor Green
Get-Item $out | ForEach-Object { Write-Host ("  {0}   {1:N1} KB" -f $_.Name, ($_.Length / 1KB)) }
Write-Host ""
Write-Host "SHA256:" -ForegroundColor Green
Write-Host ("  " + (Get-FileHash $out -Algorithm SHA256).Hash)
if (-not $isRoslyn) {
    Write-Host ""
    Write-Host "提示:当前是旧 csc,哈希不可复现;装了 VS Build Tools 会自动改用 Roslyn。" -ForegroundColor Yellow
}