"""WSGI service for self-service Samba password changes, served by Gunicorn."""
import ipaddress
import json
import logging
import os
import pwd
import re
import subprocess

USERNAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
CONFIG_PATH = os.environ.get("SAMBA_PASSWORD_CONFIG", "/etc/samba-password-api/config.json")


def config():
    with open(CONFIG_PATH, encoding="utf-8") as handle:
        values = json.load(handle)
    values["allowed_networks"] = [ipaddress.ip_network(item) for item in values["allowed_networks"]]
    return values


CONFIG = config()


def response(start_response, status, message):
    payload = json.dumps({"message": message}).encode("utf-8")
    start_response(status, [("Content-Type", "application/json; charset=utf-8"),
                            ("Content-Length", str(len(payload))), ("Connection", "close")])
    return [payload]


def change_password(username, current_password, new_password):
    account = pwd.getpwnam(username)
    result = subprocess.run(
        ["/usr/bin/setpriv", f"--reuid={account.pw_uid}", f"--regid={account.pw_gid}", "--init-groups", "/usr/bin/smbpasswd", "-s"],
        input=f"{current_password}\n{new_password}\n{new_password}\n",
        text=True, capture_output=True, timeout=20,
        env={"HOME": account.pw_dir, "USER": account.pw_name, "LOGNAME": account.pw_name, "PATH": "/usr/bin:/bin"},
    )
    diagnostic = " ".join(item.strip() for item in (result.stdout, result.stderr) if item.strip())
    logging.info("password-change user=%s result=%s exit_code=%s diagnostic=%r", username,
                 "success" if result.returncode == 0 else "denied", result.returncode, diagnostic)
    return result.returncode == 0


def app(environ, start_response):
    source = environ.get("REMOTE_ADDR", "unknown")
    try:
        if environ["REQUEST_METHOD"] != "POST" or environ["PATH_INFO"] != "/v1/change-password":
            return response(start_response, "404 Not Found", "Não encontrado.")
        if not any(ipaddress.ip_address(source) in network for network in CONFIG["allowed_networks"]):
            return response(start_response, "403 Forbidden", "Origem não autorizada.")
        size = int(environ.get("CONTENT_LENGTH", "0"))
        if size < 2 or size > 16384:
            return response(start_response, "400 Bad Request", "Solicitação inválida.")
        request = json.loads(environ["wsgi.input"].read(size))
        username, current, new = request["username"], request["currentPassword"], request["newPassword"]
        if not all(isinstance(value, str) for value in (username, current, new)) or not USERNAME.fullmatch(username):
            return response(start_response, "400 Bad Request", "Solicitação inválida.")
        if len(new) < CONFIG["minimum_password_length"] or len(new) > 128:
            return response(start_response, "400 Bad Request", "A nova senha não atende ao tamanho mínimo.")
        if change_password(username, current, new):
            logging.info("password-change source=%s user=%s result=success", source, username)
            return response(start_response, "200 OK", "Senha alterada.")
        logging.warning("password-change source=%s user=%s result=denied", source, username)
        return response(start_response, "401 Unauthorized", "Não foi possível alterar a senha.")
    except (ValueError, KeyError, json.JSONDecodeError, pwd.KeyError) as error:
        logging.warning("password-change invalid-request source=%s reason=%s", source, error)
        return response(start_response, "400 Bad Request", "Solicitação inválida.")
    except subprocess.TimeoutExpired:
        logging.warning("password-change timeout source=%s", source)
        return response(start_response, "503 Service Unavailable", "Serviço indisponível.")
    except Exception:
        logging.exception("password-change failed source=%s", source)
        return response(start_response, "500 Internal Server Error", "Não foi possível alterar a senha.")
