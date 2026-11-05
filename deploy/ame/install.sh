#!/usr/bin/env bash
# Run on the ECS after building a new, immutable release directory.
set -euo pipefail
release="$(cd -- "${1:?Usage: install.sh /opt/travel-ame/releases/RELEASE}" && pwd)"
[[ $EUID -eq 0 && "$release" == /opt/travel-ame/releases/* ]] || {
  printf '%s\n' 'Run as root with a release under /opt/travel-ame/releases.' >&2
  exit 1
}
[[ -x "$release/build/ame_worker" && -f "$release/corpus.json" ]]
install -d -m 700 /etc/travel-ame
if [[ ! -e /etc/travel-ame/token ]]; then
  (umask 077; python3 -c 'import secrets; print(secrets.token_urlsafe(48))' > /etc/travel-ame/token)
fi
# The credential stays outside releases and never appears in service arguments or logs.
install -m 644 "$release/travel-ame.service" /etc/systemd/system/travel-ame.service
ln -s "$release" /opt/travel-ame/current.next
mv -Tf /opt/travel-ame/current.next /opt/travel-ame/current
systemctl daemon-reload
systemctl enable travel-ame.service
systemctl restart travel-ame.service
