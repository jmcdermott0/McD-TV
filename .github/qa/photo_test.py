"""Profile photos end to end: send a photo for each named profile through the internet setup link, the same
"profile_photo" pieces the phone's Settings > Profile > Profile photos sends, and wait for the TV to confirm it.
The photo is drawn here at run time (Pillow); no image file is kept in the repo.
Usage: photo_test.py <setup link> p1 [p2 ...]"""
import base64, hashlib, io, json, os, sys, time, urllib.request, zlib
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from PIL import Image, ImageDraw

link = sys.argv[1].strip()
profiles = sys.argv[2:]
rid, k = link.split("#", 1)[1].split(".", 1)
key = AESGCM(base64.urlsafe_b64decode(k + "=" * (-len(k) % 4)))
COLORS = {"p1": (200, 60, 40), "p2": (40, 120, 200), "p3": (230, 180, 30)}
CHUNK = 1600  # PhotoCodec.CHUNK


def enc(obj):
    c = zlib.compressobj(9, zlib.DEFLATED, -15)
    z = c.compress(json.dumps(obj).encode()) + c.flush()
    iv = os.urandom(12)
    return "v1:" + base64.urlsafe_b64encode(iv + key.encrypt(iv, z, None)).decode().rstrip("=")


def dec(m):
    try:
        raw = base64.urlsafe_b64decode(m[3:] + "=" * (-len(m[3:]) % 4))
        return json.loads(zlib.decompress(key.decrypt(raw[:12], raw[12:], None), -15))
    except Exception:
        return None


def post(topic, body):
    urllib.request.urlopen(urllib.request.Request("https://ntfy.sh/" + topic, data=body.encode(), method="POST"), timeout=20).read()


def photo(pid):
    """A 192 px square JPEG, like the phone makes: a solid color with a light disc, different per profile."""
    img = Image.new("RGB", (192, 192), COLORS.get(pid, (90, 90, 90)))
    ImageDraw.Draw(img).ellipse((56, 40, 136, 120), fill=(240, 240, 240))
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=80)
    return base64.b64encode(buf.getvalue()).decode()


out = []
def result(ok, name):
    out.append(("PASS  " if ok else "FAIL  ") + name)
    print(out[-1])


for pid in profiles:
    b64 = photo(pid)
    v = hashlib.sha256(b64.encode("ascii")).digest()[:8].hex()  # PhotoCodec.version
    pieces = [b64[i:i + CHUNK] for i in range(0, len(b64), CHUNK)]
    req = "qa-photo-" + pid + "-" + os.urandom(4).hex()
    start = time.time()
    for i, piece in enumerate(pieces):
        post(f"mcdtv-{rid}-in", enc({"cmd": "profile_photo", "req": req, "ts": int(time.time() * 1000), "profile": pid, "v": v, "part": i, "parts": len(pieces), "data": piece}))
    got = None
    while time.time() - start < 90 and not got:
        time.sleep(4)
        txt = urllib.request.urlopen(f"https://ntfy.sh/mcdtv-{rid}-out/json?poll=1&since=5m", timeout=20).read().decode()
        for line in txt.strip().splitlines():
            ev = json.loads(line)
            s = dec(ev.get("message", "")) if ev.get("event") == "message" else None
            if s and s.get("type") == "ack" and s.get("req") == req:
                got = s
    note = (got or {}).get("note", "no answer")
    result(note.startswith("Photo saved for"), f"Profile photo for {pid} sent like the phone does ({len(pieces)} pieces): {note}")

with open("qa-out/results.txt", "a") as f:
    f.write("\n".join(out) + "\n")
sys.exit(0 if all(l.startswith("PASS") for l in out) else 1)
