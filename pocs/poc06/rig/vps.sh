#!/usr/bin/env bash
# poc-06 — SSH para a VPS (R+B) via agente 1Password, publickey-only (evita lockout de senha).
export SSH_AUTH_SOCK="$HOME/Library/Group Containers/2BUA8C4S2C.com.1password/t/agent.sock"
exec ssh -o ConnectTimeout=20 -o PreferredAuthentications=publickey -p 22022 root@143.95.220.165 "$@"
