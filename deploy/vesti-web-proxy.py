#!/usr/bin/env python3
"""Vesti web proxy.

Same-origin fetch relay for the Vesti wasm app: the browser cannot read
cross-origin feed pages or OpenGraph images because most servers do not send
CORS headers. This service fetches a caller-supplied http(s) URL server-side
and returns the bytes.

Safety:
  * only http/https
  * the host (and every redirect target) must resolve to public IPs -- no
    loopback, private, link-local, reserved or multicast addresses
  * only GET/HEAD
  * a response size cap and a timeout
  * browser requests must look same-origin/same-site (Sec-Fetch-Site), which
    blocks drive-by use from unrelated sites
"""
import ipaddress
import socket
import sys
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BIND = ("127.0.0.1", 8787)
MAX_BYTES = 25 * 1024 * 1024
TIMEOUT = 25
CHUNK = 64 * 1024
USER_AGENT = "VestiFeedProxy/1.0 (+https://app.vestifeed.org)"


def is_public_host(host: str) -> bool:
    try:
        infos = socket.getaddrinfo(host, None)
    except socket.gaierror:
        return False
    if not infos:
        return False
    for info in infos:
        addr = info[4][0]
        try:
            ip = ipaddress.ip_address(addr)
        except ValueError:
            return False
        if (
            ip.is_private
            or ip.is_loopback
            or ip.is_link_local
            or ip.is_reserved
            or ip.is_multicast
            or ip.is_unspecified
        ):
            return False
    return True


class SafeRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Rejects redirects that leave http(s) or point at a non-public host."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        parts = urllib.parse.urlsplit(newurl)
        if parts.scheme not in ("http", "https"):
            raise urllib.error.HTTPError(newurl, code, "forbidden redirect", headers, fp)
        if not parts.hostname or not is_public_host(parts.hostname):
            raise urllib.error.HTTPError(newurl, code, "forbidden redirect", headers, fp)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


OPENER = urllib.request.build_opener(SafeRedirectHandler)


class Handler(BaseHTTPRequestHandler):
    server_version = "VestiProxy/1.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))
        sys.stderr.flush()

    def do_GET(self):
        self._proxy(head=False)

    def do_HEAD(self):
        self._proxy(head=True)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _proxy(self, head: bool):
        parsed = urllib.parse.urlsplit(self.path)
        if parsed.path.rstrip("/") != "/proxy":
            self.send_error(404)
            return

        fetch_site = self.headers.get("Sec-Fetch-Site")
        if fetch_site and fetch_site not in ("same-origin", "same-site", "none"):
            self.send_error(403, "forbidden")
            return

        query = urllib.parse.parse_qs(parsed.query)
        target = (query.get("url") or [""])[0]
        if not target:
            self.send_error(400, "missing url")
            return

        parts = urllib.parse.urlsplit(target)
        if parts.scheme not in ("http", "https") or not parts.hostname:
            self.send_error(400, "unsupported url")
            return
        if not is_public_host(parts.hostname):
            self.send_error(403, "forbidden host")
            return

        request = urllib.request.Request(
            target,
            method="HEAD" if head else "GET",
            headers={"User-Agent": USER_AGENT, "Accept": "*/*"},
        )

        try:
            with OPENER.open(request, timeout=TIMEOUT) as response:
                final = urllib.parse.urlsplit(response.geturl())
                if final.scheme not in ("http", "https") or not final.hostname:
                    self.send_error(403, "forbidden redirect")
                    return
                if not is_public_host(final.hostname):
                    self.send_error(403, "forbidden redirect")
                    return

                self.send_response(response.status)
                self.send_header(
                    "Content-Type", response.headers.get("Content-Type", "application/octet-stream")
                )
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("X-Proxy-Final-Url", response.geturl())
                self.send_header("Cache-Control", "no-store")
                self.send_header("Transfer-Encoding", "chunked")
                self.end_headers()
                if head:
                    return

                total = 0
                while True:
                    chunk = response.read(CHUNK)
                    if not chunk:
                        break
                    total += len(chunk)
                    if total > MAX_BYTES:
                        break
                    self.wfile.write(b"%x\r\n%s\r\n" % (len(chunk), chunk))
                self.wfile.write(b"0\r\n\r\n")
        except urllib.error.HTTPError as error:
            # Forward the upstream status (e.g. 401/404) so callers can react.
            body = b""
            try:
                body = error.read(MAX_BYTES)
            except Exception:
                pass
            self.send_response(error.code)
            content_type = (
                error.headers.get("Content-Type", "text/plain") if error.headers else "text/plain"
            )
            self.send_header("Content-Type", content_type)
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            if not head and body:
                self.wfile.write(body)
        except Exception as error:  # noqa: BLE001
            self.send_error(502, "upstream error: %s" % error)


def main():
    server = ThreadingHTTPServer(BIND, Handler)
    server.daemon_threads = True
    server.serve_forever()


if __name__ == "__main__":
    main()
