param(
    [Parameter(Mandatory = $true)]
    [string]$Version,

    [string]$ReleaseNotes = "Melhorias e correcoes no Royal Server Access.",
    [switch]$Required,
    [string]$JpackagePath = "C:\Program Files\Java\jdk-26.0.2\bin\jpackage.exe",
    [string]$Server = "192.168.0.93",
    [string]$RemoteUser = "rmadmin",
    [string]$RemoteReleasesDirectory = "/opt/samba-password-api/releases"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$packager = Join-Path $PSScriptRoot "empacotar-exe.ps1"

if ($Version -notmatch '^\d+(\.\d+){1,3}$') {
    throw "A versao deve usar somente numeros e pontos, por exemplo: 4.2 ou 4.2.1."
}
if ($Server -notmatch '^[A-Za-z0-9.-]+$') {
    throw "O endereco do servidor contem caracteres invalidos."
}
if ($RemoteUser -notmatch '^[A-Za-z0-9._-]+$') {
    throw "O usuario SSH contem caracteres invalidos."
}
if ($RemoteReleasesDirectory -notmatch '^/[A-Za-z0-9._/-]+$') {
    throw "O diretorio remoto contem caracteres invalidos."
}
if (-not (Test-Path -LiteralPath $packager -PathType Leaf)) {
    throw "O script de empacotamento nao foi encontrado: $packager"
}
if ($null -eq (Get-Command scp.exe -ErrorAction SilentlyContinue)) {
    throw "scp.exe nao foi encontrado. Instale o Cliente OpenSSH do Windows."
}
if ($null -eq (Get-Command ssh.exe -ErrorAction SilentlyContinue)) {
    throw "ssh.exe nao foi encontrado. Instale o Cliente OpenSSH do Windows."
}

Write-Host "Etapa 1/4 - Gerando o instalador da versao $Version..."
& $packager `
    -Version $Version `
    -JpackagePath $JpackagePath `
    -ReleaseNotes $ReleaseNotes `
    -Required:$Required

$destination = Join-Path $projectRoot ("dist-installer-" + $Version)
$releaseName = "RoyalServerAccess-$Version.exe"
$releasePath = Join-Path $destination $releaseName
$manifestPath = Join-Path $destination "update.json"

if (-not (Test-Path -LiteralPath $releasePath -PathType Leaf)) {
    throw "O instalador esperado nao foi encontrado: $releasePath"
}
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "O manifesto esperado nao foi encontrado: $manifestPath"
}

$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$localHash = (Get-FileHash -LiteralPath $releasePath -Algorithm SHA256).Hash.ToLowerInvariant()
if ([string]$manifest.version -ne $Version) {
    throw "A versao do update.json nao corresponde ao instalador."
}
if ([string]$manifest.sha256 -ne $localHash) {
    throw "O SHA-256 do update.json nao corresponde ao instalador local."
}

$remoteTarget = "${RemoteUser}@${Server}"
$stagedManifestName = "RoyalServerAccess-update-$Version.json"
$stagedManifestPath = Join-Path $destination $stagedManifestName
Copy-Item -LiteralPath $manifestPath -Destination $stagedManifestPath -Force

try {
    Write-Host "Etapa 2/4 - Enviando os arquivos para $remoteTarget..."
    Write-Host "O SSH solicitara a senha do usuario $RemoteUser."
    & scp.exe -- $releasePath $stagedManifestPath "${remoteTarget}:~/"
    if ($LASTEXITCODE -ne 0) {
        throw "O envio dos arquivos por SCP falhou com o codigo $LASTEXITCODE."
    }

    Write-Host "Etapa 3/4 - Publicando a atualizacao no servidor..."
    Write-Host "O SSH e o sudo poderao solicitar suas senhas."
    $remoteCommand = "set -e; " +
        "cd ~; " +
        "python -m json.tool ~/$stagedManifestName >/dev/null; " +
        "echo '$localHash  $releaseName' | sha256sum -c -; " +
        "sudo install -d -o root -g root -m 755 '$RemoteReleasesDirectory'; " +
        "sudo install -o root -g root -m 644 ~/$releaseName '$RemoteReleasesDirectory/$releaseName'; " +
        "sudo install -o root -g root -m 644 ~/$stagedManifestName '$RemoteReleasesDirectory/update.json'; " +
        "rm -f ~/$releaseName ~/$stagedManifestName; " +
        "echo '$localHash  $RemoteReleasesDirectory/$releaseName' | sha256sum -c -; " +
        "curl -ksS 'https://$Server`:8443/v1/app-version'; echo"

    & ssh.exe -t $remoteTarget $remoteCommand
    if ($LASTEXITCODE -ne 0) {
        throw "A publicacao remota falhou com o codigo $LASTEXITCODE. Os arquivos enviados podem permanecer na pasta pessoal do servidor."
    }
}
finally {
    Remove-Item -LiteralPath $stagedManifestPath -Force -ErrorAction SilentlyContinue
}

Write-Host "Etapa 4/4 - Publicacao concluida."
Write-Host "Versao publicada: $Version"
Write-Host "Instalador: $RemoteReleasesDirectory/$releaseName"
Write-Host "SHA-256: $localHash"
