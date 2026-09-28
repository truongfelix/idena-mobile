#!/usr/bin/env python3
"""Logs the app's node on the phone every INTERVAL seconds: block, peers, node CPU and RAM, battery.

Runs over USB, or over Wi-Fi so that the phone runs on its battery (Android counts battery use only then):
    adb tcpip 5555 && adb connect <phone ip>:5555      # with the cable in, then unplug it
Usage: monitor_node.py [interval_s] [--until-synced]
    --until-synced: stop once the node reports that it is synced, and print the account.
CPU is in % of one core (the phone has 8). Needs the debuggable app (adb run-as reads the RPC key).
"""
import json
import os
import subprocess
import sys
import time
import urllib.request

PKG = "io.github.truongfelix.idenanode"
ADB = os.path.join(os.environ.get("ANDROID_HOME", ""), "platform-tools", "adb") if os.environ.get("ANDROID_HOME") else "adb"
LOCAL_PORT = 39109
NODE_PORT = 29109


def adb_shell(command):
    return subprocess.run([ADB, "shell", command], capture_output=True, text=True, timeout=60).stdout


def rpc(key, method, *params):
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": list(params), "key": key})
    request = urllib.request.Request(f"http://127.0.0.1:{LOCAL_PORT}", data=body.encode(),
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.load(response).get("result")


def sample():
    """Node process CPU ticks and RSS, phone uptime and battery, in one adb round trip."""
    out = adb_shell(
        f"p=$(pidof {PKG}:node); echo pid=$p; "
        "if [ -n \"$p\" ]; then echo ticks=$(awk '{print $14+$15}' /proc/$p/stat); "
        "echo rss=$(awk '/VmRSS/ {print $2}' /proc/$p/status); fi; "
        "echo up=$(cut -d' ' -f1 /proc/uptime); "
        "dumpsys battery"
    )
    values = {}
    for line in out.splitlines():
        # "pid=123" lines from the script, "  level: 100" lines from dumpsys battery.
        name, _, value = line.strip().partition("=" if "=" in line else ": ")
        values.setdefault(name, value)
    return values


def main():
    interval = int(sys.argv[1]) if len(sys.argv) > 1 and sys.argv[1].isdigit() else 60
    until_synced = "--until-synced" in sys.argv
    subprocess.run([ADB, "forward", f"tcp:{LOCAL_PORT}", f"tcp:{NODE_PORT}"], capture_output=True)
    key = adb_shell(f"run-as {PKG} cat files/node/rpc_key").strip()
    hz = int(adb_shell("getconf CLK_TCK").strip() or 100)
    print("time      block      highest   peers cpu%  rss_MB batt% plugged temp_C", flush=True)
    previous = None
    synced_samples = 0
    while True:
        try:
            s = sample()
            pid, ticks, up = s.get("pid", ""), s.get("ticks"), float(s.get("up", 0))
            cpu = "-"
            if previous and pid and previous[0] == pid and ticks:
                cpu = f"{(int(ticks) - previous[1]) / hz / (up - previous[2]) * 100:.0f}"
            previous = (pid, int(ticks), up) if pid and ticks else None
            plugged = "usb" if s.get("USB powered") == "true" else "ac" if s.get("AC powered") == "true" else "no"
            try:
                sync = rpc(key, "bcn_syncing")
                peers = len(rpc(key, "net_peers") or [])
                block, highest, syncing = sync["currentBlock"], sync["highestBlock"], sync["syncing"]
            except Exception:
                block, highest, peers, syncing = "-", "-", "-", True
            rss = f"{int(s['rss']) / 1024:.0f}" if s.get("rss") else "-"
            temp = f"{int(s['temperature']) / 10:.1f}" if s.get("temperature") else "-"
            print(f"{time.strftime('%H:%M:%S')} {block:>10} {highest:>10} {peers:>5} {cpu:>4} {rss:>7} "
                  f"{s.get('level', '-'):>5} {plugged:>7} {temp:>6}", flush=True)
            synced_samples = synced_samples + 1 if syncing is False else 0
            if until_synced and synced_samples >= 2:
                identity = rpc(key, "dna_identity")
                balance = rpc(key, "dna_getBalance", identity["address"])
                print(f"synced: state {identity['state']}, age {identity['age']}, stake {balance['stake']}, "
                      f"balance {balance['balance']}", flush=True)
                return
        except Exception as e:  # the phone may drop off USB for a moment
            print(f"{time.strftime('%H:%M:%S')} error: {e}", flush=True)
        time.sleep(interval)


if __name__ == "__main__":
    main()
