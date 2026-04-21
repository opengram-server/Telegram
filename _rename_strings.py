import copy
import json
import os
import re

ROOT = os.path.dirname(os.path.abspath(__file__))
RES_DIR = os.path.join(ROOT, "TMessagesProj", "src", "main", "res")

# Маппинг package_name original -> Opengram (добавляем клиентов, не заменяем)
PACKAGE_NAME_MAP = {
    "org.telegram.messenger":      "me.opengra.messenger",
    "org.telegram.messenger.beta": "me.opengra.messenger.beta",
    "org.telegram.messenger.web":  "me.opengra.messenger.web",
}

# Паттерны замены (применяются к ЗНАЧЕНИЯМ <string>, не к именам)
REPLACEMENTS = [
    # URLs (длинные паттерны первыми)
    ("telegram.org", "opengra.me"),
    ("https://t.me/", "https://opengra.me/"),
    ("ads.telegram.org", "ads.opengra.me"),
    # Brand
    ("Telegram Premium", "Opengram Premium"),
    ("Telegram Stars", "Opengram Stars"),
    ("Telegram Desktop", "Opengram Desktop"),
    ("Telegram for Android", "Opengram for Android"),
    ("Telegram for iOS", "Opengram for iOS"),
]

# Паттерны через regex (для контекстных замен)
REGEX_REPLACEMENTS = [
    (re.compile(r"(?<!\w)t\.me/"), "opengra.me/"),
    (re.compile(r"\bTELEGRAM\b"), "OPENGRAM"),
    (re.compile(r"\bTelegram\b"), "Opengram"),
    (re.compile(r"\btelegram\b"), "opengram"),
]

# <string name="..." formatted="false">VALUE</string>
STRING_RE = re.compile(
    r'(<string\s+[^>]*>)(.*?)(</string>)',
    re.DOTALL,
)


def replace_value(value: str) -> str:
    for old, new in REPLACEMENTS:
        value = value.replace(old, new)
    for pat, repl in REGEX_REPLACEMENTS:
        value = pat.sub(repl, value)
    return value


def process_file(path: str) -> int:
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()

    def _sub(m):
        return m.group(1) + replace_value(m.group(2)) + m.group(3)

    new_content = STRING_RE.sub(_sub, content)
    if new_content == content:
        return 0

    with open(path, "w", encoding="utf-8") as f:
        f.write(new_content)
    return 1


def add_opengram_clients_to_google_services(path: str) -> int:
    """Добавляет клиентов для me.opengra.* рядом с org.telegram.* (не заменяя).
    Плагин google-services ищет клиента по applicationId / namespace — нужно
    чтобы в JSON были оба варианта пакетов."""
    if not os.path.isfile(path):
        return 0
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)

    existing = set()
    for c in data.get("client", []):
        pkg = c.get("client_info", {}).get("android_client_info", {}).get("package_name")
        if pkg:
            existing.add(pkg)

    added = 0
    new_clients = []
    for client in data.get("client", []):
        pkg = client.get("client_info", {}).get("android_client_info", {}).get("package_name", "")
        if pkg in PACKAGE_NAME_MAP:
            new_pkg = PACKAGE_NAME_MAP[pkg]
            if new_pkg not in existing:
                dup = copy.deepcopy(client)
                dup["client_info"]["android_client_info"]["package_name"] = new_pkg
                new_clients.append(dup)
                existing.add(new_pkg)
                added += 1

    if added:
        data.setdefault("client", []).extend(new_clients)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)
    return added


def main():
    total_files = 0
    total_changed = 0

    for dirname in sorted(os.listdir(RES_DIR)):
        full = os.path.join(RES_DIR, dirname)
        if not os.path.isdir(full) or not dirname.startswith("values"):
            continue
        strings_file = os.path.join(full, "strings.xml")
        if not os.path.isfile(strings_file):
            continue
        total_files += 1
        if process_file(strings_file):
            total_changed += 1
            print(f"changed: {os.path.relpath(strings_file, ROOT)}")

    # Verify main file
    main_file = os.path.join(RES_DIR, "values", "strings.xml")
    with open(main_file, "r", encoding="utf-8") as f:
        new = f.read()

    gs_total = 0
    for module in os.listdir(ROOT):
        gs_path = os.path.join(ROOT, module, "google-services.json")
        added = add_opengram_clients_to_google_services(gs_path)
        if added:
            print(f"patched {module}/google-services.json: +{added} client(s)")
            gs_total += added

    print(f"\nProcessed: {total_files} strings files, changed: {total_changed}")
    print(f"google-services.json: added {gs_total} Opengram client(s) total")
    print(f"Telegram remaining in values/strings.xml: {new.count('Telegram')}")
    print(f"Opengram count in values/strings.xml:    {new.count('Opengram')}")


if __name__ == "__main__":
    main()