# Migração para Gunicorn

No Arch Linux, execute como `root`:

```bash
pacman -Syu --needed gunicorn
install -m 644 samba_password_wsgi.py /opt/samba-password-api/
install -m 644 samba-password-gunicorn.service /etc/systemd/system/
systemctl disable --now samba-password-api
systemctl daemon-reload
systemctl enable --now samba-password-gunicorn
systemctl status samba-password-gunicorn --no-pager
```

O serviço mantém HTTPS na porta 8443 e reutiliza o certificado e o `config.json` existentes.

Para ver os logs:

```bash
journalctl -u samba-password-gunicorn -f
```
