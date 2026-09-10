param(
    [string]$Version = "3.9",
    [string]$JpackagePath
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot

if ([string]::IsNullOrWhiteSpace($JpackagePath)) {
    $command = Get-Command jpackage.exe -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        $JpackagePath = $command.Source
    }
}

if ([string]::IsNullOrWhiteSpace($JpackagePath) -or -not (Test-Path -LiteralPath $JpackagePath)) {
    throw "jpackage.exe nao foi encontrado. Informe o caminho com -JpackagePath, por exemplo: 'C:\Program Files\Java\jdk-21\bin\jpackage.exe'."
}

$wixCommandsAvailable = ($null -ne (Get-Command candle.exe -ErrorAction SilentlyContinue)) -and ($null -ne (Get-Command light.exe -ErrorAction SilentlyContinue))

if (-not $wixCommandsAvailable) {
    $wixLocations = @(
        "C:\Program Files (x86)\WiX Toolset v3.11\bin",
        "C:\Program Files\WiX Toolset v3.11\bin"
    )
    foreach ($wixLocation in $wixLocations) {
        $candlePath = Join-Path $wixLocation "candle.exe"
        $lightPath = Join-Path $wixLocation "light.exe"
        if ((Test-Path -LiteralPath $candlePath) -and (Test-Path -LiteralPath $lightPath)) {
            $env:Path = $wixLocation + ";" + $env:Path
            $wixCommandsAvailable = $true
            Write-Host "WiX Toolset encontrado em: $wixLocation"
            break
        }
    }
}

if (-not $wixCommandsAvailable) {
    throw "O WiX Toolset 3 nao foi encontrado. Instale o WiX 3.11 antes de empacotar."
}

mvn clean package
mvn dependency:copy-dependencies "-DoutputDirectory=target\libs"

$destination = Join-Path $projectRoot ("dist-installer-" + $Version)
New-Item -ItemType Directory -Path $destination -Force | Out-Null

& $JpackagePath `
    --type exe `
    --name "Royal Server Access" `
    --app-version $Version `
    --vendor "Royal Max" `
    --description "Gerenciador de acesso ao servidor Samba" `
    --icon packaging\server-access.ico `
    --input target `
    --main-jar samba-manager-0.1.0.jar `
    --main-class br.com.suaempresa.sambamanager.SambaManagerApp `
    --module-path target\libs `
    --add-modules javafx.controls,javafx.graphics,javafx.base,java.net.http `
    --java-options "-Dsamba.manager.version=$Version" `
    --win-menu `
    --win-menu-group "Royal Max" `
    --win-shortcut `
    --win-dir-chooser `
    --win-per-user-install `
    --dest $destination

if ($LASTEXITCODE -ne 0) {
    throw "Nao foi possivel gerar o instalador."
}

Write-Host "Instalador criado em: $destination"
