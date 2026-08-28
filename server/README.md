# Serviço de alteração de senha — Arch Linux

O serviço atende somente HTTPS na porta 8443. Ele não registra senhas; executa `smbpasswd -s` com a identidade do usuário solicitado, portanto o Samba confirma a senha atual antes da alteração.

## Instalação no servidor

Copie esta pasta para o servidor e execute, como `root`:

```bash
pacman -Syu --needed python openssl samba
install -d -m 755 /opt/samba-password-api /etc/samba-password-api
install -m 755 samba-password-api.py /opt/samba-password-api/
install -m 600 config.json.example /etc/samba-password-api/config.json
install -m 644 samba-password-api.service /etc/systemd/system/
./create-internal-ca.sh 192.168.0.93
systemctl daemon-reload
systemctl enable --now samba-password-api
```

Verifique: `systemctl status samba-password-api --no-pager`.

## Confiar na CA nas máquinas Windows

Copie apenas `royal-samba-ca.crt` para a máquina Windows (nunca copie o arquivo `.key`). Em PowerShell aberto como administrador:

```powershell
Import-Certificate -FilePath .\royal-samba-ca.crt -CertStoreLocation Cert:\LocalMachine\Root
```

Depois, a aplicação poderá chamar `https://192.168.0.93:8443/v1/change-password` sem alertas de certificado.
