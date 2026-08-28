#!/usr/bin/env python3
"""Minimal HTTPS API for self-service Samba password changes."""
import ipaddress
import json
import logging
import pwd
import re
import ssl
import subprocess
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

USERNAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")


def load_config(path):
    with open(path, encoding="utf-8") as handle:
        config = json.load(handle)
    config["allowed_networks"] = [ipaddress.ip_network(item) for item in config["allowed_networks"]]
    return config


def change_password(username, current_password, new_password):
    account = pwd.getpwnam(username)
    logging.info("smbpasswd starting user=%s uid=%s", username, account.pw_uid)
    # setpriv avoids unsafe Python fork hooks in the threaded HTTPS server.
    # As the account itself, smbpasswd verifies the current password before changing it.
    result = subprocess.run(
        ["/usr/bin/setpriv", f"--reuid={account.pw_uid}", f"--regid={account.pw_gid}", "--init-groups", "/usr/bin/smbpasswd", "-s"],
        input=f"{current_password}\n{new_password}\n{new_password}\n",
        text=True,
        capture_output=True,
        timeout=20,
        env={"HOME": account.pw_dir, "USER": account.pw_name, "LOGNAME": account.pw_name, "PATH": "/usr/bin:/bin"},
    )
    # smbpasswd never receives a password through arguments and its output contains no password.
    diagnostic = " ".join(part.strip() for part in (result.stdout, result.stderr) if part.strip())
    logging.info("smbpasswd finished user=%s exit_code=%s diagnostic=%r", username, result.returncode, diagnostic)
    return result.returncode == 0, result.returncode, diagnostic


class Handler(BaseHTTPRequestHandler):
    config = None

    def log_message(self, format, *args):
        logging.info("%s - %s", self.client_address[0], format % args)

    def respond(self, status, body):
        payload = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(payload)
        self.wfile.flush()
        self.close_connection = True

    def do_POST(self):
        if self.path != "/v1/change-password":
            self.respond(404, {"message": "Não encontrado."})
            return
        try:
            source = ipaddress.ip_address(self.client_address[0])
            if not any(source in network for network in self.config["allowed_networks"]):
                self.respond(403, {"message": "Origem não autorizada."})
                return
            size = int(self.headers.get("Content-Length", "0"))
            if size < 2 or size > 16384:
                raise ValueError
            request = json.loads(self.rfile.read(size))
            username = request["username"]
            current = request["currentPassword"]
            new = request["newPassword"]
            if not all(isinstance(value, str) for value in (username, current, new)) or not USERNAME.fullmatch(username):
                raise ValueError
            if len(new) < self.config["minimum_password_length"] or len(new) > 128:
                self.respond(400, {"message": "A nova senha não atende ao tamanho mínimo."})
                return
            changed, exit_code, diagnostic = change_password(username, current, new)
            logging.info("password-change user=%s source=%s result=%s exit_code=%s diagnostic=%r", username, source,
                         "success" if changed else "denied", exit_code, diagnostic)
            self.respond(200 if changed else 401, {"message": "Senha alterada." if changed else "Não foi possível alterar a senha."})
        except (ValueError, KeyError, json.JSONDecodeError) as error:
            logging.warning("password-change invalid-request source=%s reason=%s", self.client_address[0], error)
            self.respond(400, {"message": "Solicitação inválida."})
        except subprocess.TimeoutExpired:
            logging.warning("password-change timeout source=%s", self.client_address[0])
            self.respond(503, {"message": "Serviço indisponível."})
        except Exception:
            logging.exception("password-change failed source=%s", self.client_address[0])
            self.respond(500, {"message": "Não foi possível alterar a senha."})


def main():
    config = load_config(sys.argv[1])
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
        handlers=[logging.FileHandler("/var/log/samba-password-api.log", encoding="utf-8"), logging.StreamHandler()],
    )
    Handler.config = config
    server = ThreadingHTTPServer((config["bind_address"], config["port"]), Handler)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(config["certificate"], config["private_key"])
    server.socket = context.wrap_socket(server.socket, server_side=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
