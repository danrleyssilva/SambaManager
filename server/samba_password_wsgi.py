"""WSGI service for self-service Samba password changes, served by Gunicorn."""
import ipaddress
import json
import logging
from logging.handlers import WatchedFileHandler
import os
from pathlib import Path
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
RELEASES_DIRECTORY = Path(CONFIG.get("releases_directory", "/opt/samba-password-api/releases"))
AUDIT_LOG_PATH = CONFIG.get("audit_log", "/var/log/royal-server-access-audit.log")


def audit_logger():
    logger = logging.getLogger("royal-server-access-audit")
    logger.setLevel(logging.INFO)
    logger.propagate = False
    if not logger.handlers:
        handler = WatchedFileHandler(AUDIT_LOG_PATH, encoding="utf-8")
        handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
        logger.addHandler(handler)
    return logger


AUDIT = audit_logger()


def json_response(start_response, status, value, cache_control="no-store"):
    payload = json.dumps(value, ensure_ascii=False).encode("utf-8")
    start_response(status, [("Content-Type", "application/json; charset=utf-8"),
                            ("Content-Length", str(len(payload))),
                            ("Cache-Control", cache_control),
                            ("Connection", "close")])
    return [payload]


def response(start_response, status, message):
    return json_response(start_response, status, {"message": message})


def release_file(environ, start_response, filename):
    if not re.fullmatch(r"[A-Za-z0-9._-]+\.exe", filename, re.IGNORECASE):
        return response(start_response, "404 Not Found", "Não encontrado.")
    release = (RELEASES_DIRECTORY / filename).resolve()
    if release.parent != RELEASES_DIRECTORY.resolve() or not release.is_file():
        return response(start_response, "404 Not Found", "Não encontrado.")
    size = release.stat().st_size
    start_response("200 OK", [
        ("Content-Type", "application/vnd.microsoft.portable-executable"),
        ("Content-Length", str(size)),
        ("Content-Disposition", f'attachment; filename="{release.name}"'),
        ("Cache-Control", "private, no-cache"),
        ("Connection", "close"),
    ])
    handle = release.open("rb")
    wrapper = environ.get("wsgi.file_wrapper")
    if wrapper:
        return wrapper(handle, 1024 * 1024)

    def chunks():
        try:
            while data := handle.read(1024 * 1024):
                yield data
        finally:
            handle.close()

    return chunks()


def app_version(start_response):
    manifest_path = RELEASES_DIRECTORY / "update.json"
    with manifest_path.open(encoding="utf-8") as handle:
        manifest = json.load(handle)
    required = ("version", "downloadUrl", "sha256", "required", "notes")
    if not isinstance(manifest, dict) or any(key not in manifest for key in required):
        raise ValueError("update.json inválido")
    return json_response(start_response, "200 OK", manifest)


def change_password(username, current_password, new_password):
    account = pwd.getpwnam(username)
    result = subprocess.run(
        ["/usr/bin/setpriv", f"--reuid={account.pw_uid}", f"--regid={account.pw_gid}", "--init-groups", "/usr/bin/smbpasswd", "-s"],
        input=f"{current_password}\n{new_password}\n{new_password}\n",
        text=True, capture_output=True, timeout=20,
        env={"HOME": account.pw_dir, "USER": account.pw_name, "LOGNAME": account.pw_name, "PATH": "/usr/bin:/bin"},
    )
    diagnostic = " ".join(item.strip() for item in (result.stdout, result.stderr) if item.strip())
    logging.info("smbpasswd user=%s result=%s exit_code=%s diagnostic=%r", username,
                 "success" if result.returncode == 0 else "denied", result.returncode, diagnostic)
    return result.returncode == 0


def safe_log_value(value, maximum=128):
    return "".join(character if character.isprintable() else "?" for character in value)[:maximum]


def audit_login(environ, start_response, source):
    size = int(environ.get("CONTENT_LENGTH", "0"))
    if size < 2 or size > 4096:
        return response(start_response, "400 Bad Request", "Solicitação inválida.")
    request = json.loads(environ["wsgi.input"].read(size))
    username = request["username"]
    computer = request.get("computerName", "unknown")
    version = request.get("appVersion", "unknown")
    accessible_count = request.get("accessibleCount", 0)
    if (not isinstance(username, str) or not USERNAME.fullmatch(username)
            or not isinstance(computer, str) or not isinstance(version, str)
            or not isinstance(accessible_count, int) or accessible_count < 0 or accessible_count > 1000):
        return response(start_response, "400 Bad Request", "Solicitação inválida.")
    AUDIT.info("event=connection result=success user=%s source=%s computer=%s app_version=%s accessible_shares=%s",
               username, source, safe_log_value(computer), safe_log_value(version, 32), accessible_count)
    return response(start_response, "200 OK", "Conexão registrada.")


def app(environ, start_response):
    source = environ.get("REMOTE_ADDR", "unknown")
    try:
        if not any(ipaddress.ip_address(source) in network for network in CONFIG["allowed_networks"]):
            return response(start_response, "403 Forbidden", "Origem não autorizada.")
        method = environ.get("REQUEST_METHOD", "")
        path = environ.get("PATH_INFO", "")
        if method == "GET" and path == "/v1/app-version":
            logging.info("update-check source=%s", source)
            return app_version(start_response)
        if method == "GET" and path.startswith("/releases/"):
            filename = path.removeprefix("/releases/")
            logging.info("update-download source=%s file=%s", source, filename)
            return release_file(environ, start_response, filename)
        if method == "POST" and path == "/v1/audit-login":
            return audit_login(environ, start_response, source)
        if method != "POST" or path != "/v1/change-password":
            return response(start_response, "404 Not Found", "Não encontrado.")
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
            AUDIT.info("event=password-change result=success user=%s source=%s", username, source)
            return response(start_response, "200 OK", "Senha alterada.")
        logging.warning("password-change source=%s user=%s result=denied", source, username)
        AUDIT.warning("event=password-change result=denied user=%s source=%s", username, source)
        return response(start_response, "401 Unauthorized", "Não foi possível alterar a senha.")
    except FileNotFoundError as error:
        logging.warning("resource-not-found source=%s reason=%s", source, error)
        return response(start_response, "404 Not Found", "Não encontrado.")
    except (ValueError, KeyError, json.JSONDecodeError, pwd.KeyError) as error:
        logging.warning("password-change invalid-request source=%s reason=%s", source, error)
        return response(start_response, "400 Bad Request", "Solicitação inválida.")
    except subprocess.TimeoutExpired:
        logging.warning("password-change timeout source=%s", source)
        return response(start_response, "503 Service Unavailable", "Serviço indisponível.")
    except Exception:
        logging.exception("password-change failed source=%s", source)
        return response(start_response, "500 Internal Server Error", "Não foi possível alterar a senha.")
