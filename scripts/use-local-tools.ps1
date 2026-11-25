$toolRoot = Join-Path (Split-Path -Parent $PSScriptRoot) '.archive/toolchain'

$jdk = Get-ChildItem -LiteralPath (Join-Path $toolRoot 'corretto21') -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/javac.exe') } |
    Select-Object -First 1
$node = Get-ChildItem -LiteralPath (Join-Path $toolRoot 'node') -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'npm.cmd') } |
    Select-Object -First 1

$portablePaths = @()
if ($jdk) {
    $env:JAVA_HOME = $jdk.FullName
    $portablePaths += Join-Path $jdk.FullName 'bin'
}
if ($node) {
    $portablePaths += $node.FullName
}
if ($portablePaths) {
    $existingPaths = $env:Path -split ';' | Where-Object { $_ -notin $portablePaths }
    $env:Path = ($portablePaths + $existingPaths) -join ';'
}
