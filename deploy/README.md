# Web deployment

Files backing **https://app.vestifeed.org** on the `vesti` host.

| File | Installed to |
| --- | --- |
| `app.vestifeed.org.caddy` | `/etc/caddy/conf.d/app.vestifeed.org` |
| `vesti-web-proxy.py` | `/usr/local/bin/vesti-web-proxy.py` (mode 755) |
| `vesti-web-proxy.service` | `/etc/systemd/system/vesti-web-proxy.service` |

Deploy the bundle and (re)install the config:

```bash
./gradlew :webApp:wasmJsBrowserDistribution
tar -C webApp/build/dist/wasmJs/productionExecutable -czf - . \
  | ssh vesti 'tar -xzf - -C /srv/http/app.vestifeed.org'

scp deploy/vesti-web-proxy.py            vesti:/usr/local/bin/vesti-web-proxy.py
scp deploy/vesti-web-proxy.service       vesti:/etc/systemd/system/vesti-web-proxy.service
scp deploy/app.vestifeed.org.caddy       vesti:/etc/caddy/conf.d/app.vestifeed.org
ssh vesti 'chmod +x /usr/local/bin/vesti-web-proxy.py
  systemctl daemon-reload
  systemctl enable --now vesti-web-proxy.service
  caddy validate --config /etc/caddy/Caddyfile && systemctl reload caddy'
```

The Caddy site sends `Cross-Origin-Opener-Policy: same-origin` and
`Cross-Origin-Embedder-Policy: require-corp` (needed for the SQLite OPFS
worker) and reverse-proxies `/proxy*` to the proxy service. The proxy only
relays http(s) to public IPs, rejecting loopback/private/link-local addresses
on the initial request and on every redirect.
