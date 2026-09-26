#!/usr/bin/env python3
"""Minimal msgfmt: compile GNU gettext .po -> binary .mo.

Supports the subset fcitx5 uses:
  * header entry (msgid "")
  * plain entries (msgid -> msgstr)
  * msgctxt context entries (stored as "context\\x04msgid")
  * plural forms (msgid_plural / msgstr[n]) -- kept for robustness

MO format (little-endian), no hash table:
  [0..3]  magic 0x950412de
  [4..7]  version 0
  [8..11] N (number of strings)
  [12..15] O (offset of original-string table)
  [16..19] T (offset of translated-string table)
  [20..23] hash table size S = 0
  [24..27] hash table offset H = 0
  then N * (len,offset) for originals, N * (len,offset) for translations,
  then original strings (NUL-separated), then translated strings.
"""

import re
import shutil
import struct
import sys

MAGIC = 0x950412DE


def unescape(s):
    """Decode PO string escapes (\\n, \\t, \\\", \\\\, octal)."""
    out = []
    i = 0
    n = len(s)
    simple = {
        "n": "\n", "t": "\t", "r": "\r", "a": "\a", "b": "\b",
        "f": "\f", "v": "\v", '"': '"', "\\": "\\",
    }
    while i < n:
        c = s[i]
        if c == "\\" and i + 1 < n:
            nxt = s[i + 1]
            if nxt in simple:
                out.append(simple[nxt])
                i += 2
                continue
            if nxt in "01234567":
                num = ""
                j = i + 1
                while j < n and s[j] in "01234567" and len(num) < 3:
                    num += s[j]
                    j += 1
                out.append(chr(int(num, 8)))
                i = j
                continue
            out.append(c)
            out.append(nxt)
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


_LITERAL_RE = re.compile(r'"((?:[^"\\]|\\.)*)"')


def _read_literals(lines, i):
    """Collect adjacent string literals starting at line i (they may span lines)."""
    vals = []
    while i < len(lines):
        m = re.match(r'^\s*"((?:[^"\\]|\\.)*)"\s*$', lines[i])
        if m:
            vals.append(m.group(1))
            i += 1
        else:
            break
    return unescape("".join(vals)), i


def parse_po(text):
    entries = []
    lines = text.split("\n")
    n = len(lines)
    i = 0
    cur = {}

    def finalize(entry):
        if not entry:
            return None
        msgid = entry.get("msgid", "")
        ctxt = entry.get("ctxt")
        if ctxt:
            msgid = ctxt + "\x04" + msgid
        if "msgid_plural" in entry:
            key = entry["msgid"] + "\x00" + entry["msgid_plural"]
            plurals = entry.get("msgstr_plural", {})
            strs = [plurals[k] for k in sorted(plurals)]
            return (key, "\x00".join(strs))
        return (msgid, entry.get("msgstr", ""))

    while i < n:
        line = lines[i]
        s = line.strip()
        if not s or s.startswith("#"):
            i += 1
            continue

        m = re.match(r'^([A-Za-z_][A-Za-z0-9_]*)(?:\[(\d+)\])?\s*(.*)$', s)
        if not m:
            i += 1
            continue
        kw = m.group(1)
        idx = m.group(2)
        rest = m.group(3).strip()

        if kw == "msgid" and cur.get("msgid") is not None:
            entries.append(finalize(cur))
            cur = {}

        if rest.startswith('"'):
            inline = "".join(unescape(x) for x in _LITERAL_RE.findall(rest))
            i += 1
            more, i = _read_literals(lines, i)
            val = inline + more
        else:
            i += 1
            val, i = _read_literals(lines, i)

        if kw == "msgctxt":
            cur["ctxt"] = val
        elif kw == "msgid":
            cur["msgid"] = val
        elif kw == "msgid_plural":
            cur["msgid_plural"] = val
        elif kw == "msgstr":
            if idx is not None:
                cur.setdefault("msgstr_plural", {})[int(idx)] = val
            else:
                cur["msgstr"] = val
        # ignore other keywords (e.g. obsolete entries under #~)

    entries.append(finalize(cur))
    return [e for e in entries if e is not None]


def build_mo(entries):
    ids = [e[0].encode("utf-8") for e in entries]
    strs = [e[1].encode("utf-8") for e in entries]
    N = len(entries)
    header_size = 28
    orig_tab_off = header_size                 # descriptor table for originals
    trans_tab_off = header_size + 8 * N        # descriptor table for translations
    data_off = header_size + 16 * N            # string data begins here

    # Every string (including the last) is NUL-terminated in GNU MO format.
    orig_data = b"\x00".join(ids) + b"\x00"
    orig_offsets = []
    cur = data_off
    for b in ids:
        orig_offsets.append(cur)
        cur += len(b) + 1

    # Compute absolute offset of every translated string.
    trans_data = b"\x00".join(strs) + b"\x00"
    trans_offsets = []
    cur = data_off + len(orig_data)
    for b in strs:
        trans_offsets.append(cur)
        cur += len(b) + 1

    buf = bytearray()
    buf += struct.pack("<I", MAGIC)
    buf += struct.pack("<I", 0)          # revision
    buf += struct.pack("<I", N)
    buf += struct.pack("<I", orig_tab_off)
    buf += struct.pack("<I", trans_tab_off)
    buf += struct.pack("<I", 0)          # hash table size
    buf += struct.pack("<I", 0)          # hash table offset

    # Original string descriptors (length, offset).
    for b, off in zip(ids, orig_offsets):
        buf += struct.pack("<I", len(b))
        buf += struct.pack("<I", off)
    # Translated string descriptors (length, offset).
    for b, off in zip(strs, trans_offsets):
        buf += struct.pack("<I", len(b))
        buf += struct.pack("<I", off)

    buf += orig_data
    buf += trans_data
    return bytes(buf)


def desktop_mode(args):
    """Handle `msgfmt --desktop|--xml -d DIR --template T -o OUT [--keyword=...]`.

    Parses --template and -o, ignores translation catalogs, and copies the
    template to the output verbatim.
    """
    template = None
    out_path = None
    i = 0
    n = len(args)
    while i < n:
        a = args[i]
        if a == "--template":
            if i + 1 >= n:
                break
            template = args[i + 1]
            i += 2
        elif a.startswith("--template="):
            template = a.split("=", 1)[1]
            i += 1
        elif a == "-o":
            if i + 1 >= n:
                break
            out_path = args[i + 1]
            i += 2
        elif a in ("-d", "-l", "--locale"):
            i += 2  # skip option and its value
        elif a == "-k":
            i += 2
        elif a.startswith("--keyword"):
            i += 1
        else:
            i += 1
    if not template or not out_path:
        sys.stderr.write("msgfmt: desktop/xml mode requires --template and -o\n")
        return 2
    shutil.copyfile(template, out_path)
    return 0


def main(argv):
    if len(argv) < 2:
        sys.stderr.write("usage: msgfmt.py [-o OUT.mo] FILE.po\n")
        return 2

    args = argv[1:]
    if "--version" in args:
        print("msgfmt (GNU gettext-tools) 0.22")
        return 0

    # Desktop Entry / XML mode: merge .po translations into a template.
    # For fcitx5-android, .conf translations are resolved at runtime by
    # gettext and .desktop files are unused on Android, so emitting the
    # template verbatim (original msgids) is the correct minimal behavior.
    if "--desktop" in args or "--xml" in args:
        return desktop_mode(args)

    # .po -> .mo mode: parse -o, tolerate common msgfmt options (e.g.
    # --no-hash --endianness=little injected by fcitx5's HookAddCustomCommand).
    out_path = None
    po_path = None
    i = 0
    n = len(args)
    valueless = {
        "-c", "-v", "-h", "--check", "--check-format", "--check-domain",
        "--check-header", "--no-hash", "--statistics", "--verbose",
        "--no-location", "--sorted-output", "--strict", "--use-fuzzy",
        "--use-first", "--no-wrap", "--help",
    }
    valued = {"-o", "--output-file", "-e", "--endianness", "-l", "--locale",
              "-d", "--directory"}
    while i < n:
        a = args[i]
        if a in valued:
            if a in ("-o", "--output-file"):
                if i + 1 < n:
                    out_path = args[i + 1]
            i += 2
        elif a.startswith("--endianness="):
            i += 1
        elif a in valueless:
            i += 1
        elif a.startswith("-") and a not in ("-",):
            i += 1  # unknown option
        else:
            po_path = a
            i += 1

    if po_path is None:
        sys.stderr.write("msgfmt.py: no input .po file\n")
        return 2
    if out_path is None:
        out_path = po_path[:-3] + ".mo" if po_path.endswith(".po") else po_path + ".mo"

    with open(po_path, "r", encoding="utf-8") as f:
        text = f.read()
    entries = parse_po(text)
    data = build_mo(entries)
    with open(out_path, "wb") as f:
        f.write(data)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
