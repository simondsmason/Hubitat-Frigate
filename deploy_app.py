#!/usr/bin/env python3
"""
Hubitat App/Driver Deployment Script for Frigate Integration

Deploys Groovy source code to a Hubitat hub via direct HTTP POST to the
hub's internal JSON API endpoints. No browser automation required (~1s deploy).

Endpoints used:
  - /hub2/userAppTypes     - list user app types (for name → ID resolution)
  - /hub2/userDeviceTypes  - list user driver types (for name → ID resolution)
  - /app/ajax/code?id=N    - GET current app source + version
  - /driver/ajax/code?id=N - GET current driver source + version
  - /app/saveOrUpdateJson  - POST app source code
  - /driver/saveOrUpdateJson - POST driver source code

Reference implementation: Unraid-Browser-Automation/browserless-tools/hubitat_browser.py
"""

import sys
import re
import json
import requests
from pathlib import Path

# Default hub IP for C8-2 (can be overridden with --hub-ip)
DEFAULT_HUB_IP = "192.168.2.222"  # HubitatC8-2


# ---------------------------------------------------------------------------
# HTTP helpers
# ---------------------------------------------------------------------------

def hubitat_get_json(hub_ip, path, timeout=30):
    """GET a JSON endpoint from a Hubitat hub and return parsed response."""
    url = f"http://{hub_ip}{path}"
    try:
        resp = requests.get(url, timeout=timeout)
        resp.raise_for_status()
        return resp.json()
    except requests.exceptions.HTTPError as e:
        print(f"ERROR: HTTP {e.response.status_code} for {path}: {e.response.text[:300]}")
        sys.exit(1)
    except requests.exceptions.ConnectionError:
        print(f"ERROR: Cannot reach hub at {hub_ip}")
        sys.exit(1)
    except requests.exceptions.Timeout:
        print(f"ERROR: Timeout reaching hub at {hub_ip}{path}")
        sys.exit(1)


def hubitat_post_json(hub_ip, path, payload, timeout=30):
    """POST JSON to a Hubitat hub endpoint and return parsed response."""
    url = f"http://{hub_ip}{path}"
    try:
        resp = requests.post(url, json=payload, timeout=timeout)
        resp.raise_for_status()
        return resp.json()
    except requests.exceptions.HTTPError as e:
        print(f"ERROR: HTTP {e.response.status_code} for POST {path}: {e.response.text[:300]}")
        sys.exit(1)
    except requests.exceptions.ConnectionError:
        print(f"ERROR: Cannot reach hub at {hub_ip}")
        sys.exit(1)
    except requests.exceptions.Timeout:
        print(f"ERROR: Timeout reaching hub at {hub_ip}{path}")
        sys.exit(1)


# ---------------------------------------------------------------------------
# Name resolution
# ---------------------------------------------------------------------------

def parse_groovy_name(source):
    """Extract name from a Groovy app/driver definition() block.

    Returns name string or None if not found.
    """
    m = re.search(r'definition\s*\(\s*name\s*:\s*["\']([^"\']+)["\']', source)
    if m:
        return m.group(1)
    # Fallback: name: "..." anywhere in file
    m = re.search(r'name:\s*["\']([^"\']+)["\']', source)
    return m.group(1) if m else None


def detect_component_type(source):
    """Determine if source is an app or driver.

    Drivers have 'capability' declarations inside their metadata block; apps don't.
    """
    if 'capability "' in source or "capability '" in source:
        return "driver"
    return "app"


def resolve_type_id(hub_ip, name, component_type):
    """Resolve a user app/driver name to its type ID via hub JSON API.

    Returns {"id": int, "name": str} on single match.
    Exits on no match or ambiguous match.
    """
    if component_type == "app":
        endpoint = "/hub2/userAppTypes"
        label = "app"
    else:
        endpoint = "/hub2/userDeviceTypes"
        label = "driver"

    data = hubitat_get_json(hub_ip, endpoint)
    if not isinstance(data, list):
        print(f"ERROR: Unexpected response from {endpoint}: expected list")
        sys.exit(1)

    q = name.strip().lower()
    matches = []
    for item in data:
        item_name = (item.get("name") or "").strip()
        if q == item_name.lower():
            # Exact match
            return {"id": item["id"], "name": item_name}
        if q in item_name.lower():
            matches.append({"id": item["id"], "name": item_name})

    if len(matches) == 1:
        return matches[0]
    if len(matches) == 0:
        print(f"ERROR: No {label} type found matching: '{name}' on hub {hub_ip}")
        sys.exit(1)
    print(f"ERROR: Multiple {label} types match '{name}'; be more specific:")
    for m in matches[:15]:
        print(f"  {m['id']}: {m['name']}")
    sys.exit(1)


# ---------------------------------------------------------------------------
# Deploy
# ---------------------------------------------------------------------------

def deploy(hub_ip, file_path, component_type=None, type_id=None):
    """Deploy a Groovy source file to a Hubitat hub via direct HTTP POST.

    Steps:
      1. Read source file
      2. Resolve type ID (auto-detect or explicit)
      3. GET current version from hub
      4. POST save with {id, version, source}
      5. Check result for compilation errors

    Returns True on success, False on failure.
    """
    path = Path(file_path)
    if not path.exists():
        print(f"ERROR: File not found: {file_path}")
        return False

    # 1. Read source
    with open(path, "r", encoding="utf-8") as f:
        source = f.read()

    if not source.strip():
        print(f"ERROR: File is empty: {file_path}")
        return False

    print(f"Loaded: {path.name} ({len(source)} chars)")

    # Auto-detect component type if not specified
    if not component_type:
        component_type = detect_component_type(source)
    print(f"Component type: {component_type}")

    # Set endpoints based on type
    if component_type == "app":
        code_endpoint = "/app/ajax/code"
        save_endpoint = "/app/saveOrUpdateJson"
    else:
        code_endpoint = "/driver/ajax/code"
        save_endpoint = "/driver/saveOrUpdateJson"

    # 2. Resolve type ID
    if type_id:
        type_name = f"(ID {type_id})"
    else:
        name = parse_groovy_name(source)
        if not name:
            name = path.stem.replace("-", " ").replace("_", " ")
            print(f"Warning: Could not parse name from definition(), using filename: {name}")
        resolved = resolve_type_id(hub_ip, name, component_type)
        type_id = resolved["id"]
        type_name = resolved["name"]

    print(f"Deploying: {type_name} (type ID: {type_id}) to {hub_ip}")

    # 3. GET current version
    current = hubitat_get_json(hub_ip, f"{code_endpoint}?id={type_id}")
    current_version = current.get("version", 0)

    # 4. POST save
    payload = {
        "id": type_id,
        "version": current_version,
        "source": source,
    }
    result = hubitat_post_json(hub_ip, save_endpoint, payload)

    # 5. Check result
    if result.get("success"):
        new_version = result.get("version", current_version + 1)
        print(f"✅ Deployed successfully (version {current_version} → {new_version})")
        return True
    else:
        message = result.get("message", "Unknown error")
        print(f"❌ Deploy failed: {message}")
        # Check for compilation errors in the response
        errors = result.get("errors", [])
        if errors:
            for err in errors:
                print(f"  - {err}")
        return False


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 deploy_app.py <code_path> [options]")
        print()
        print("Deploys Groovy app/driver code to Hubitat via direct HTTP (~1s).")
        print("Auto-detects app vs driver from source code.")
        print("Auto-resolves type ID by matching name on hub.")
        print()
        print("Options:")
        print("  code_path         Path to the .groovy file (required)")
        print("  --hub-ip <ip>     Hub IP (default: 192.168.2.222 for C8-2)")
        print("  --id <type_id>    Explicit type ID (skips auto-resolution)")
        print()
        print("Examples:")
        print("  python3 deploy_app.py 'Frigate Parent App.groovy'")
        print("  python3 deploy_app.py 'Frigate Camera Device.groovy'")
        print("  python3 deploy_app.py 'Frigate MQTT Bridge Device.groovy'")
        print("  python3 deploy_app.py 'Frigate Parent App.groovy' --hub-ip 192.168.2.200")
        print("  python3 deploy_app.py 'Frigate Parent App.groovy' --id 447")
        sys.exit(1)

    # Parse arguments
    code_path = None
    hub_ip = DEFAULT_HUB_IP
    type_id = None

    i = 1
    while i < len(sys.argv):
        arg = sys.argv[i]
        if arg == "--hub-ip" and i + 1 < len(sys.argv):
            hub_ip = sys.argv[i + 1]
            i += 1
        elif arg == "--id" and i + 1 < len(sys.argv):
            type_id = sys.argv[i + 1]
            i += 1
        elif arg in ("--headless", "--auto"):
            pass  # Ignored for backwards compatibility
        elif arg.startswith("http://") or arg.startswith("https://"):
            pass  # Ignored for backwards compatibility (old URL-based approach)
        elif not code_path:
            code_path = arg
        i += 1

    if not code_path:
        print("ERROR: code_path is required")
        sys.exit(1)

    success = deploy(hub_ip, code_path, type_id=type_id)
    sys.exit(0 if success else 1)
