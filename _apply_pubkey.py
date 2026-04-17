import hashlib
import os
import re
import struct

try:
    from cryptography.hazmat.primitives.serialization import load_pem_public_key
except ImportError:
    raise SystemExit("Install: pip install cryptography")

HERE = os.path.dirname(os.path.abspath(__file__))
PUBKEY_PATH = os.path.join(HERE, "pubkey.asc")
JNI_DIR = os.path.join(HERE, "TMessagesProj", "jni", "tgnet")
HANDSHAKE_CPP = os.path.join(JNI_DIR, "Handshake.cpp")
DATACENTER_CPP = os.path.join(JNI_DIR, "Datacenter.cpp")


def tl_string(b: bytes) -> bytes:
    """Encode bytes as TL string (with length prefix and 4-byte padding)."""
    if len(b) <= 253:
        result = bytes([len(b)]) + b
    else:
        result = bytes([254]) + len(b).to_bytes(3, "little") + b
    while len(result) % 4:
        result += b"\x00"
    return result


def compute_fingerprint(pem: str) -> int:
    """MTProto rsa_public_key fingerprint = last 8 bytes of sha1(TL serialize n,e), little-endian uint64."""
    key = load_pem_public_key(pem.encode())
    nums = key.public_numbers()
    n_bytes = nums.n.to_bytes((nums.n.bit_length() + 7) // 8, "big")
    e_bytes = nums.e.to_bytes((nums.e.bit_length() + 7) // 8, "big")
    tl = tl_string(n_bytes) + tl_string(e_bytes)
    digest = hashlib.sha1(tl).digest()
    return struct.unpack("<Q", digest[-8:])[0]


with open(PUBKEY_PATH, "r", encoding="utf-8") as f:
    pem = f.read().strip()

m = re.search(
    r"-----BEGIN RSA PUBLIC KEY-----\s*(.*?)\s*-----END RSA PUBLIC KEY-----",
    pem, re.DOTALL,
)
if not m:
    raise SystemExit("pubkey.asc doesn't look like PEM RSA PUBLIC KEY")

body_lines = [ln.strip() for ln in m.group(1).splitlines() if ln.strip()]
fingerprint = compute_fingerprint(pem)
print(f"Computed fingerprint: 0x{fingerprint:016x}")

placeholder_re = re.compile(
    r'(?:OPENGRAM_PUBKEY_LINE_\d+_HERE\\n"\s*\n\s*"?)+',
)


def patch_file(path: str) -> int:
    with open(path, "r", encoding="utf-8") as f:
        text = f.read()

    n = 0

    # Each placeholder line "OPENGRAM_PUBKEY_LINE_N_HERE\n" → real key body line.
    # Build a new replacement preserving the surrounding C++ structure.
    # The placeholders are 6 lines, replace all of them at once with body_lines.
    pattern = re.compile(
        r'((?:\s+"?\s*)?OPENGRAM_PUBKEY_LINE_1_HERE\\n"\s*\n)'
        r'(\s+")OPENGRAM_PUBKEY_LINE_2_HERE\\n"\s*\n'
        r'\s+"OPENGRAM_PUBKEY_LINE_3_HERE\\n"\s*\n'
        r'\s+"OPENGRAM_PUBKEY_LINE_4_HERE\\n"\s*\n'
        r'\s+"OPENGRAM_PUBKEY_LINE_5_HERE\\n"\s*\n'
        r'\s+"OPENGRAM_PUBKEY_LINE_6_HERE\\n"',
    )

    def _sub(m):
        nonlocal n
        n += 1
        prefix_first = m.group(1).rstrip()  # whitespace+ "PLACEHOLDER\n"
        indent = m.group(2)  # whitespace + "
        # Replace placeholder content with real key lines
        out = re.sub(r'OPENGRAM_PUBKEY_LINE_1_HERE', body_lines[0], prefix_first) + "\n"
        for i, line in enumerate(body_lines[1:], start=2):
            out += f'{indent}{line}\\n"'
            if i < len(body_lines):
                out += "\n"
        return out

    text, replaced = pattern.subn(_sub, text)
    n = replaced

    # Fingerprint
    if "0xOPENGRAM_FINGERPRINT_HERE" in text:
        text = text.replace("0xOPENGRAM_FINGERPRINT_HERE", f"0x{fingerprint:016x}")
        n += 1

    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    return n


total = 0
for path in (HANDSHAKE_CPP, DATACENTER_CPP):
    cnt = patch_file(path)
    print(f"{os.path.relpath(path, HERE)}: {cnt} replacement(s)")
    total += cnt

if total == 0:
    raise SystemExit("Nothing was replaced — already applied?")

print(f"\nDone. Total replacements: {total}")
