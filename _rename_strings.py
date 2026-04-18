import os
import re
import json
import glob

ROOT = os.path.dirname(os.path.abspath(__file__))
RES_DIR = os.path.join(ROOT, "TMessagesProj", "src", "main", "res")

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

# Маппинг package names для google-services.json
PACKAGE_NAME_MAP = {
    "org.telegram.messenger":      "me.opengra.messenger",
    "org.telegram.messenger.beta": "me.opengra.messenger.beta",
    "org.telegram.messenger.web":  "me.opengra.messenger.web",
}

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

    backup_path = path + ".bak"
    if not os.path.exists(backup_path):
        with open(backup_path, "w", encoding="utf-8") as f:
            f.write(content)

    def _sub(m):
        return m.group(1) + replace_value(m.group(2)) + m.group(3)

    new_content = STRING_RE.sub(_sub, content)
    if new_content == content:
        return 0

    with open(path, "w", encoding="utf-8") as f:
        f.write(new_content)
    return 1


def patch_google_services(path: str) -> bool:
    """Заменяет package_name org.telegram.* -> me.opengra.* в google-services.json."""
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)

    changed = False
    clients = data.get("client", [])
    for client in clients:
        client_info = client.get("client_info", {})
        android_info = client_info.get("android_client_info", {})
        pkg = android_info.get("package_name", "")
        if pkg in PACKAGE_NAME_MAP:
            android_info["package_name"] = PACKAGE_NAME_MAP[pkg]
            changed = True

    if changed:
        backup_path = path + ".bak"
        if not os.path.exists(backup_path):
            with open(path, "r", encoding="utf-8") as f:
                pass  # already read above
            import shutil
            shutil.copy2(path, backup_path)

        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)
        print(f"patched google-services.json: {os.path.relpath(path, ROOT)}")

    return changed


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

    # Патчим все google-services.json в проекте
    gs_files = glob.glob(os.path.join(ROOT, "**/google-services.json"), recursive=True)
    gs_patched = 0
    for gs_path in gs_files:
        if gs_path.endswith(".bak"):
            continue
        try:
            if patch_google_services(gs_path):
                gs_patched += 1
        except Exception as e:
            print(f"warning: could not patch {gs_path}: {e}")

    # Verify main file
    main_file = os.path.join(RES_DIR, "values", "strings.xml")
    with open(main_file, "r", encoding="utf-8") as f:
        new = f.read()

    print(f"\nProcessed: {total_files} strings files, changed: {total_changed}")
    print(f"Patched google-services.json: {gs_patched}")
    print(f"Telegram remaining in values/strings.xml: {new.count('Telegram')}")
    print(f"Opengram count in values/strings.xml:    {new.count('Opengram')}")


if __name__ == "__main__":
    main()