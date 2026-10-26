# CleanYourFire dal PC Windows: quello che fa l'app "Pulizia Fire TV", via adb sulla rete.
# Serve solo se l'app non riesce a collegarsi da sola al debug ADB della stick.
#
#   .\tools\cleanyourfire.ps1 192.168.1.50                    backup + pulizia (tutte le categorie) + launcher pulito
#   .\tools\cleanyourfire.ps1 192.168.1.50 -Install           come sopra, installando prima dist\CleanYourFire.apk
#   .\tools\cleanyourfire.ps1 192.168.1.50 -Skip alexa,apps   salta categorie (telemetry, ads, apps, alexa)
#   .\tools\cleanyourfire.ps1 192.168.1.50 -NoLauncher        solo pulizia, Home di Amazon invariata
#   .\tools\cleanyourfire.ps1 192.168.1.50 -Restore           riattiva tutto e spegne il launcher pulito
#
# Serve adb.exe (Android platform-tools) nel PATH, oppure $env:ADB con il suo percorso.
# Se PowerShell blocca lo script: powershell -ExecutionPolicy Bypass -File .\tools\cleanyourfire.ps1 <IP>
param(
    [Parameter(Mandatory = $true, Position = 0)][string]$TvHost,
    [switch]$Restore,
    [switch]$Install,
    [switch]$NoLauncher,
    [string[]]$Skip = @()
)
$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
$List = Join-Path $Root 'launcher/app/src/main/assets/debloat-fire-os7.txt'
$Apk = Join-Path $Root 'dist/CleanYourFire.apk'
$Pkg = 'it.cleanyourfire.home'
$Service = "$Pkg/com.gathin.tvhome.HomeKeyService"
$RecordDevice = '/sdcard/Download/cleanyourfire-ripristino.txt'
$Adb = if ($env:ADB) { $env:ADB } else { 'adb' }
$PackageName = '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$'

if ($TvHost -notmatch ':') { $TvHost = "${TvHost}:5555" }
$StateDir = Join-Path $Root ('backup/' + ($TvHost -split ':')[0])
New-Item -ItemType Directory -Force -Path $StateDir | Out-Null
$RecordLocal = Join-Path $StateDir 'cleanyourfire-ripristino.txt'
$AlarmLocal = Join-Path $StateDir 'alarm_manager_constants.originale'

function Tv([string]$Command) {
    # One string: adb hands it to the stick's shell, which reads the quotes.
    $out = & $Adb -s $TvHost shell $Command 2>&1
    return (($out | ForEach-Object { "$_" }) -join "`n").Replace("`r", '')
}

function Read-Record {
    $lines = @()
    if (Test-Path $RecordLocal) { $lines += Get-Content $RecordLocal }
    $lines += (Tv "cat $RecordDevice 2>/dev/null") -split "`n"
    return @($lines | ForEach-Object { $_.Trim() } | Where-Object { $_ -match $PackageName } | Select-Object -Unique)
}

function Write-Record([string[]]$Packages) {
    $text = (@($Packages | Sort-Object -Unique) -join "`n")
    if ($text) { $text += "`n" }
    [IO.File]::WriteAllText($RecordLocal, $text)
    & $Adb -s $TvHost push $RecordLocal $RecordDevice | Out-Null
}

function Launcher-Off {
    $services = @((Tv 'settings get secure enabled_accessibility_services') -split ':' |
        Where-Object { $_ -and $_ -ne 'null' -and $_ -ne $Service })
    if ($services.Count -eq 0) {
        Tv 'settings delete secure enabled_accessibility_services' | Out-Null
        Tv 'settings put secure accessibility_enabled 0' | Out-Null
    } else {
        Tv ("settings put secure enabled_accessibility_services '" + ($services -join ':') + "'") | Out-Null
    }
    if (Test-Path $AlarmLocal) {
        $alarm = [IO.File]::ReadAllText($AlarmLocal)
        if ($alarm) { Tv "settings put global alarm_manager_constants '$alarm'" | Out-Null }
        else { Tv 'settings delete global alarm_manager_constants' | Out-Null }
        Remove-Item $AlarmLocal
    }
    Write-Host 'Launcher pulito disattivato.'
}

& $Adb connect $TvHost | Out-Null
& $Adb -s $TvHost get-state 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Error "Nessuna risposta da $TvHost. Controlla Debug ADB e accetta la richiesta sulla TV, poi riprova."
}
Write-Host ("Connesso a ${TvHost}: " + (Tv 'getprop ro.product.model').Trim() + ', ' + (Tv 'getprop ro.build.version.name').Trim())

if ($Restore) {
    $n = 0
    foreach ($p in Read-Record) {
        $out = Tv "pm enable $p"
        if ($out -match 'new state: enabled') { $n++ } else { Write-Host "  errore  ${p}: $out" }
    }
    Write-Record @()
    Write-Host "Riattivate $n app."
    Launcher-Off
    exit 0
}

# 1. Backup, on the PC.
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$all = Tv 'pm list packages'
$off = Tv 'pm list packages -d'
[IO.File]::WriteAllText((Join-Path $StateDir "backup-$stamp-tutte.txt"), $all)
[IO.File]::WriteAllText((Join-Path $StateDir "backup-$stamp-disattivate.txt"), $off)
[IO.File]::WriteAllText((Join-Path $StateDir "backup-$stamp-global.txt"), (Tv 'settings list global'))
[IO.File]::WriteAllText((Join-Path $StateDir "backup-$stamp-secure.txt"), (Tv 'settings list secure'))
Write-Host "Backup in $StateDir\backup-$stamp-*.txt"

# 2. Optional install of the app.
if ($Install) {
    if (-not (Test-Path $Apk)) { Write-Error "Manca $Apk" }
    & $Adb -s $TvHost install -r $Apk
}

# 3. Disable the chosen categories.
$installed = @{}; foreach ($l in $all -split "`n") { if ($l -like 'package:*') { $installed[$l.Substring(8).Trim()] = 1 } }
$disabled = @{}; foreach ($l in $off -split "`n") { if ($l -like 'package:*') { $disabled[$l.Substring(8).Trim()] = 1 } }
$record = [Collections.Generic.List[string]]::new()
foreach ($p in Read-Record) { $record.Add($p) }
$done = 0; $already = 0; $missing = 0; $failed = 0; $category = ''
foreach ($line in Get-Content $List -Encoding UTF8) {
    if ($line -match '^##\s*\[([a-z0-9_-]+)\]') { $category = $Matches[1]; Write-Host "- $line"; continue }
    $p = ($line -split '#')[0].Trim()
    if (-not $p) { continue }
    if ($Skip -contains $category) { continue }
    if ($p -notmatch $PackageName) { Write-Error "Riga non valida nella lista: $p" }
    if (-not $installed.ContainsKey($p)) { $missing++; continue }
    if ($disabled.ContainsKey($p)) { $already++; continue }
    $out = Tv "pm disable-user --user 0 $p"
    if ($out -match 'new state: disabled-user') { $done++; $record.Add($p); Write-Host "  off  $p" }
    else { $failed++; Write-Host "  errore  ${p}: $out" }
}
Write-Record $record.ToArray()
Write-Host "Fatto: $done disattivate, $already gia' spente, $missing non presenti, $failed errori o protette."
Write-Host "Registro di ripristino: $RecordLocal (copia sulla TV: $RecordDevice)"

# 4. Clean launcher: Home redirect through the app's accessibility service.
if (-not $NoLauncher) {
    if ((Tv "pm list packages $Pkg") -notmatch [regex]::Escape("package:$Pkg")) {
        Write-Host "App $Pkg non installata: installa dist\CleanYourFire.apk (o usa -Install) per il launcher pulito."
        exit 0
    }
    Tv "pm grant $Pkg android.permission.WRITE_SECURE_SETTINGS" | Out-Null
    Tv "pm grant $Pkg android.permission.ACCESS_FINE_LOCATION" | Out-Null
    $alarm = (Tv 'settings get global alarm_manager_constants').Trim()
    if ($alarm -eq 'null') { $alarm = '' }
    if (-not (Test-Path $AlarmLocal)) { [IO.File]::WriteAllText($AlarmLocal, $alarm) }
    $parts = @($alarm -split ',' | Where-Object { $_ -and $_ -notlike 'min_futurity=*' }) + 'min_futurity=0'
    Tv ("settings put global alarm_manager_constants '" + ($parts -join ',') + "'") | Out-Null
    $services = @((Tv 'settings get secure enabled_accessibility_services').Trim() -split ':' |
        Where-Object { $_ -and $_ -ne 'null' -and $_ -ne $Service }) + $Service
    Tv ("settings put secure enabled_accessibility_services '" + ($services -join ':') + "'") | Out-Null
    Tv 'settings put secure accessibility_enabled 1' | Out-Null
    Tv "am start -n $Pkg/com.gathin.tvhome.MainActivity" | Out-Null
    Write-Host 'Launcher pulito attivo: il tasto Home porta a CleanYourFire.'
}
Write-Host "Fine. Per chiudere il debug ADB: adb -s $TvHost shell settings put global adb_enabled 0"
