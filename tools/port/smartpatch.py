"""Apply a Paper/Purpur-style unified diff to a NeoForge-decompiled source file.

Paper and Purpur ship their minecraft patches against sources whose
parameters and locals carry descriptive names (`amplifier`, `bytes`,
`builder`); NeoForge's decompile of the same file still uses `p_296356_`,
`abyte`, `p_341859_`. Every hunk therefore fails on context with GNU patch.

A hunk matches a window of the target when every token is equal, except
where the target holds a decompiler-generated name -- those establish a
name mapping, which is then applied to the lines the hunk adds. Mappings
are per line, because a name like `builder` is a different lambda
parameter on each line of a registry block. Added lines inherit the
mapping of the line they replace.

Never rewritten: method names (token followed by `(`), qualified-name
segments (token after `.`), and anything inside a string literal or a
comment -- the three ways the earlier rename tooling corrupted files.
"""
import re, sys

TOK = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*|\d+\.?\d*[fFdDlL]?|\S")
IDENT = re.compile(r"^[A-Za-z_$][A-Za-z0-9_$]*$")
# Names the decompiler generates: p_NNNNN_, or a lowercase run with optional digits.
DECOMPILED = re.compile(r"^(?:p_\d+_|[a-z][a-z0-9]*\d*)$")
KEYWORDS = set("""abstract assert boolean break byte case catch char class const continue default do double
else enum extends final finally float for goto if implements import instanceof int interface long native new
package private protected public return short static strictfp super switch synchronized this throw throws
transient try void volatile while true false null var record yield sealed permits non""".split())


def _pair_ok(a, b, a_prev, b_prev, a_next, b_next, local):
    if a == b:
        return True
    if not (IDENT.match(a) and IDENT.match(b)):
        return False
    if a in KEYWORDS or b in KEYWORDS or not DECOMPILED.match(a):
        return False
    if a_next == "(" and b_next == "(":
        return False                      # a method name, not a local
    if a_prev == "." or b_prev == ".":
        return False                      # qualified-name segment / member access
    if local.get(b, a) != a:
        return False
    for k, v in local.items():
        if v == a and k != b:
            return False                  # keep the mapping injective
    local[b] = a
    return True


def line_match(fileline, patchline):
    """Returns the patch-name -> target-name mapping for this line, or None."""
    ft, pt = TOK.findall(fileline), TOK.findall(patchline)
    if len(ft) != len(pt):
        return None
    local = {}
    for i, (a, b) in enumerate(zip(ft, pt)):
        if not _pair_ok(a, b,
                        ft[i - 1] if i else "", pt[i - 1] if i else "",
                        ft[i + 1] if i + 1 < len(ft) else "", pt[i + 1] if i + 1 < len(pt) else "",
                        local):
            return None
    return local


def block_match(lines, start, old):
    """Returns a per-old-line list of mappings, or None."""
    maps = []
    for i, o in enumerate(old):
        if start + i >= len(lines):
            return None
        m = line_match(lines[start + i], o)
        if m is None:
            return None
        maps.append(m)
    return maps


def rename(line, mapping):
    """Rename mapped identifiers, skipping qualified-name segments, string and
    char literals, and comments."""
    if not mapping:
        return line
    out, i, n = [], 0, len(line)
    while i < n:
        c = line[i]
        if c == "/" and i + 1 < n and line[i + 1] == "/":
            out.append(line[i:]); break
        if c == "/" and i + 1 < n and line[i + 1] == "*":
            j = line.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append(line[i:j]); i = j; continue
        if c in "\"'":
            j, q = i + 1, c
            while j < n:
                if line[j] == "\\":
                    j += 2; continue
                if line[j] == q:
                    j += 1; break
                j += 1
            out.append(line[i:j]); i = j; continue
        m = re.match(r"[A-Za-z_$][A-Za-z0-9_$]*", line[i:])
        if m:
            word = m.group(0)
            joined = "".join(out)
            prev = next((ch for ch in reversed(joined) if not ch.isspace()), "")
            out.append(word if prev == "." else mapping.get(word, word))
            i += len(word); continue
        out.append(c); i += 1
    return "".join(out)


def parse_hunks(text):
    hunks, cur, remaining = [], None, 0
    for ln in text.split("\n"):
        m = re.match(r"^@@ -(\d+)(?:,(\d+))? \+(?:_|\d+)(?:,(\d+))? @@", ln)
        if m:
            oldn = int(m.group(2) or 1)
            cur = {"start": int(m.group(1)), "lines": [], "oldn": oldn}
            hunks.append(cur)
            remaining = oldn
            continue
        if cur is None or remaining <= 0 or ln.startswith("\\"):
            continue
        if ln[:1] in (" ", "-"):
            cur["lines"].append(ln); remaining -= 1
        elif ln[:1] == "+":
            cur["lines"].append(ln)
        elif ln == "":
            cur["lines"].append(" "); remaining -= 1
    return hunks


def _emit(hlines, filelines, start, maps):
    """Rebuild the window: context kept verbatim, added lines renamed with the
    mapping of the line they replace (or the nearest preceding matched line)."""
    out, fi, oi, cur = [], start, 0, {}
    for l in hlines:
        if l[0] in (" ", "-"):
            cur = maps[oi] if oi < len(maps) else cur
            if l[0] == " ":
                out.append(filelines[fi])
            fi += 1; oi += 1
        else:
            out.append(rename(l[1:], cur))
    return out, fi


def token_stream(lines):
    return [(t, li, k == 0) for li, ln in enumerate(lines) for k, t in enumerate(TOK.findall(ln))]


def stream_match(stream, old_lines):
    """Match a hunk's old side ignoring how either side is wrapped."""
    old_toks = []
    for ln in old_lines:
        old_toks.extend(TOK.findall(ln))
    if not old_toks:
        return None
    n, m, hits = len(stream), len(old_toks), []
    for s in range(n - m + 1):
        if not stream[s][2] or (s + m < n and not stream[s + m][2]):
            continue
        local, ok = {}, True
        for i in range(m):
            a, b = stream[s + i][0], old_toks[i]
            if not _pair_ok(a, b,
                            stream[s + i - 1][0] if s + i else "", old_toks[i - 1] if i else "",
                            stream[s + i + 1][0] if s + i + 1 < n else "", old_toks[i + 1] if i + 1 < m else "",
                            local):
                ok = False; break
        if ok:
            hits.append((stream[s][1], stream[s + m - 1][1], local))
            if len(hits) > 1:
                return None
    return hits[0] if hits else None


def _brace_delta(text_lines):
    return sum(l.count("{") - l.count("}") for l in text_lines)


def _hunk_brace_delta(h):
    return (_brace_delta([l[1:] for l in h["lines"] if l[0] == "+"])
            - _brace_delta([l[1:] for l in h["lines"] if l[0] == "-"]))


def apply_patch(path, patchtext, fuzz=80):
    lines = open(path, encoding="utf-8").read().split("\n")
    original = list(lines)
    expected = _brace_delta(lines)
    results, offset = [], 0
    for h in parse_hunks(patchtext):
        old = [l[1:] for l in h["lines"] if l[0] in (" ", "-")]
        new_side = [l[1:] for l in h["lines"] if l[0] in (" ", "+")]
        guess = max(0, h["start"] - 1 + offset)
        window = range(max(0, guess - fuzz), guess + fuzz + 1)
        # Idempotence: if this hunk's result is already present, do not apply it
        # again. Checked before the old side, because a pure-addition hunk's old
        # side (all context) still matches after the hunk has been applied.
        if any(block_match(lines, s, new_side) is not None for s in window):
            results.append(("SKIP-APPLIED", h))
            continue
        added = [l[1:] for l in h["lines"] if l[0] == "+"]
        removed = [l for l in h["lines"] if l[0] == "-"]
        if added and not removed:
            # A pure-addition hunk whose added lines were merged earlier and then
            # edited (by a later Purpur hunk, say) no longer matches as a block,
            # but its old side - all context - still does. Applying it again would
            # insert a second copy, so look for its most distinctive added line.
            probe = max(added, key=lambda l: len(TOK.findall(l)))
            # Searched over the whole file, not just the window: an earlier
            # session may have merged this block somewhere else entirely, and
            # the old side of a pure-addition hunk matches almost anywhere.
            if len(TOK.findall(probe)) >= 6 and any(
                    line_match(l, probe) is not None for l in lines):
                results.append(("SKIP-APPLIED", h))
                continue
        cands = [guess] + [guess + d for d in range(1, fuzz + 1)] + [guess - d for d in range(1, fuzz + 1)]
        hit = next(((s, m) for s in cands if s >= 0 and (m := block_match(lines, s, old)) is not None), None)
        if hit is not None:
            s, maps = hit
            out, fi = _emit(h["lines"], lines, s, maps)
            lines[s:fi] = out
            offset += len(out) - (fi - s)
            expected += _hunk_brace_delta(h)
            results.append(("OK", h))
            continue
        sm = stream_match(token_stream(lines), old)
        if sm is None:
            results.append(("REJECT", h))
            continue
        first, last, m = sm
        new = [rename(l[1:], m) for l in h["lines"] if l[0] in (" ", "+")]
        lines[first:last + 1] = new
        offset += len(new) - (last + 1 - first)
        expected += _hunk_brace_delta(h)
        results.append(("OK-REWRAP", h))
    if _brace_delta(lines) != expected:
        # A hunk landed somewhere that cost the file a line. Refuse the whole
        # file rather than leave it unparseable - a syntax error here hides
        # every other error in the build.
        return original, [("REJECT", h) for _, h in results], {}
    return lines, results, {}


if __name__ == "__main__":
    lines, results, _ = apply_patch(sys.argv[1], open(sys.argv[2], encoding="utf-8").read())
    ok = sum(1 for r, _ in results if r != "REJECT")
    if any(r.startswith("OK") for r, _ in results):
        open(sys.argv[1], "w", encoding="utf-8").write("\n".join(lines))
    print(f"{ok}/{len(results)}")
    sys.exit(0 if ok == len(results) else 1)
