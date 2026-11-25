$ErrorActionPreference = 'Stop'

$project = Split-Path -Parent $PSScriptRoot
$fixturePath = Join-Path $project 'fixtures/mvp.json'
$organization = (Get-Content -LiteralPath $fixturePath -Raw -Encoding utf8 | ConvertFrom-Json).organization

function To-Utf8SqlLiteral([string] $Value) {
    $hex = [Convert]::ToHexString([Text.Encoding]::UTF8.GetBytes($Value))
    return "CONVERT(X'$hex' USING utf8mb4)"
}

$kind = To-Utf8SqlLiteral $organization.kind
$code = To-Utf8SqlLiteral $organization.code
$name = To-Utf8SqlLiteral $organization.name
$timezone = To-Utf8SqlLiteral $organization.timezone

$statement = @"
INSERT INTO org_unit (kind, code, name, timezone)
VALUES ($kind, $code, $name, $timezone)
ON DUPLICATE KEY UPDATE id = id;
SELECT code, kind, name FROM org_unit WHERE code = $code;
"@

$statement | docker compose --project-directory $project exec -T mysql sh -c 'export MYSQL_PWD="$MYSQL_PASSWORD"; exec mysql --default-character-set=utf8mb4 --user="$MYSQL_USER" "$MYSQL_DATABASE"'
if ($LASTEXITCODE -ne 0) {
    throw 'Demo organization loading failed. Run the Java application once to apply Flyway migrations.'
}
