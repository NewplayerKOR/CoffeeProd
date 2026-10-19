#!/usr/bin/env bash
set -euo pipefail
umask 077
client_ip=${1:-}
client_port=${2:-}
if [[ ! "$client_ip" =~ ^192\.168\.0\.[0-9]{1,3}$ || ! "$client_port" =~ ^[0-9]{1,5}$ ]] ||
   ((10#$client_port < 1 || 10#$client_port > 65535)); then
  printf 'Usage: bash capture-ubuntu-ssh-headers.sh <Windows private IP> <SSH client port>\n' >&2
  exit 1
fi
client_port=$((10#$client_port))
if ((10#${client_ip##*.} > 255)); then
  printf 'Invalid private IPv4 address.\n' >&2
  exit 1
fi
if ! command -v tcpdump >/dev/null; then
  printf 'tcpdump is missing. Install it before capture; this script does not install packages.\n' >&2
  exit 1
fi
sudo -v
directory=$(mktemp -d "$HOME/coffeeprod-packet-headers-XXXXXXXX")
printf 'Capture directory: %s\nWait for tcpdump listening message, then start Windows capture/probe.\n' "$directory"
filter="ip and tcp and ((host $client_ip and port 22 and port $client_port) or port 8080)"
printf '{"client_ip":"%s","client_port":%s,"snaplen":60,"datalink":"LINUX_SLL2","maximum_seconds":300,"started_at_utc":"%s"}\n' \
  "$client_ip" "$client_port" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$directory/conditions.json"
if sudo timeout --signal=INT --kill-after=5s 300s tcpdump -p -i any -y LINUX_SLL2 -nn -U -s 60 -c 20000 -w - "$filter" \
   > "$directory/ubuntu-headers.pcap" 2> >(tee "$directory/tcpdump-stderr.txt" >&2); then
  status=0
else
  status=$?
fi
printf 'capture_exit_code=%s\nended_at_utc=%s\n' "$status" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$directory/completion.txt"
printf 'Capture ended. Share ubuntu-headers.pcap and tcpdump-stderr.txt from: %s\n' "$directory"
if ((status != 0 && status != 124 && status != 130)); then exit "$status"; fi
