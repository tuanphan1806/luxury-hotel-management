$ErrorActionPreference = 'Stop'
$qaRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $qaRoot 'compose.qa.yml'
$evidenceDir = Join-Path $qaRoot 'output/isolated-qa-2026-09-25'
New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null

function Docker-Checked {
    # Keep native flags such as -d/-U out of PowerShell parameter binding.
    $qaDockerArgs = $args
    $result = & docker @qaDockerArgs
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed: $($qaDockerArgs[0])" }
    return $result
}

$dbContainer = 'hotel-isolated-qa-postgres-1'
$backendContainer = 'hotel-isolated-qa-backend-1'
$containers = Docker-Checked inspect $dbContainer $backendContainer | ConvertFrom-Json
foreach ($container in $containers) {
    if ($container.Config.Labels.'com.docker.compose.project' -ne 'hotel-isolated-qa') {
        throw 'Refusing non-QA container'
    }
    $networks = @($container.NetworkSettings.Networks.PSObject.Properties.Name)
    if ($networks.Count -ne 1 -or $networks[0] -ne 'hotel-isolated-qa_qa') {
        throw 'Refusing unexpected network'
    }
}
$network = @(Docker-Checked network inspect hotel-isolated-qa_qa | ConvertFrom-Json)[0]
if (-not $network.Internal) { throw 'QA network must block outbound traffic' }
$volume = $containers[0].Mounts | Where-Object Destination -eq '/var/lib/postgresql/data'
if ($volume.Name -ne 'hotel-isolated-qa_qa-data') { throw 'Refusing shared database volume' }
if ($containers[1].Config.Env -notcontains 'DATABASE_URL=jdbc:postgresql://postgres:5432/hotel_qa') {
    throw 'Backend datasource is not the isolated QA database'
}

# Only synthetic QA data is exported. Never point this script at Neon or a shared DB.
$restoreDb = 'hotel_qa_restore_' + (Get-Date -Format 'yyyyMMddHHmmss')
$manifestSql = @'
\set ON_ERROR_STOP on
CREATE TEMP TABLE qa_manifest (table_name text, row_count bigint, digest text);
DO $$
DECLARE t record;
BEGIN
  FOR t IN SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename LOOP
    EXECUTE format(
      'INSERT INTO qa_manifest SELECT %L, count(*), md5(COALESCE(string_agg(row_to_json(r)::text, chr(10) ORDER BY row_to_json(r)::text), %L)) FROM public.%I r',
      t.tablename, '', t.tablename);
  END LOOP;
END $$;
SELECT table_name || '|' || row_count || '|' || digest FROM qa_manifest ORDER BY table_name;
'@
$manifestPath = Join-Path $evidenceDir 'manifest.sql'
[IO.File]::WriteAllText($manifestPath, $manifestSql, [Text.UTF8Encoding]::new($false))
Docker-Checked cp $manifestPath "${dbContainer}:/tmp/qa-manifest.sql"
Docker-Checked cp (Join-Path $qaRoot 'code/backend/db/postgres/post-cutover-validate.sql') "${dbContainer}:/tmp/qa-validate.sql"

$restoreCreated = $false
Docker-Checked compose -f $composeFile stop backend
try {
    $backupTimer = [Diagnostics.Stopwatch]::StartNew()
    Docker-Checked exec $dbContainer pg_dump -U hotel_qa -d hotel_qa --format=custom --file=/tmp/hotel-qa.dump
    $backupTimer.Stop()
    $before = @(Docker-Checked exec $dbContainer psql -U hotel_qa -d hotel_qa -Atq -f /tmp/qa-manifest.sql)
    Docker-Checked exec $dbContainer createdb -U hotel_qa $restoreDb
    $restoreCreated = $true
    $restoreTimer = [Diagnostics.Stopwatch]::StartNew()
    Docker-Checked exec $dbContainer pg_restore -U hotel_qa -d $restoreDb --exit-on-error --no-owner /tmp/hotel-qa.dump
    $restoreTimer.Stop()
    $after = @(Docker-Checked exec $dbContainer psql -U hotel_qa -d $restoreDb -Atq -f /tmp/qa-manifest.sql)
    if (Compare-Object $before $after) { throw 'Restored row counts or digests differ' }
    Docker-Checked exec $dbContainer psql -U hotel_qa -d $restoreDb -v ON_ERROR_STOP=1 -f /tmp/qa-validate.sql |
        Set-Content -LiteralPath (Join-Path $evidenceDir 'restore-validation.log')
    $before | Set-Content -LiteralPath (Join-Path $evidenceDir 'restore-manifest.txt')
    Docker-Checked cp "${dbContainer}:/tmp/hotel-qa.dump" (Join-Path $evidenceDir 'synthetic-qa.dump')
    [pscustomobject]@{
        scope = 'isolated synthetic QA, not production backup or measured production RPO/RTO'
        tablesCompared = $before.Count
        backupMilliseconds = $backupTimer.ElapsedMilliseconds
        restoreMilliseconds = $restoreTimer.ElapsedMilliseconds
        rowCountsAndDigestsMatch = $true
        postCutoverValidationPassed = $true
    } | ConvertTo-Json | Tee-Object -FilePath (Join-Path $evidenceDir 'restore-result.json')
} finally {
    if ($restoreCreated) { Docker-Checked exec $dbContainer dropdb -U hotel_qa $restoreDb }
    Docker-Checked compose -f $composeFile start backend
}
