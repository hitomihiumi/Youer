"""Resolve identifiers that Paper/Purpur's added lines refer to but that the
NeoForge decompile names differently.

Usage:
    ./gradlew :youer:compileJava > build.log 2>&1
    python3 tools/port/resolve.py build.log write

Driven by javac: for each "cannot find symbol / variable X" it finds the
enclosing method in the target, collects the parameters and the locals
declared above the failing line, and picks the one whose declared type
matches the name Paper used. Ambiguous cases are reported, never guessed.
"""
import re, sys, os, collections
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from smartpatch import rename


SIG = re.compile(r"^(?P<indent>\s*)(?:(?:public|protected|private|static|final|abstract|synchronized|native|default|strictfp)\s+)*"
                 r"(?:<[^>]*>\s*)?[\w.$<>\[\],?\s]+\s+(?P<name>\w+)\s*\((?P<params>[^;{]*)\)\s*(?:throws [\w.,\s]+)?\{\s*$")
DECL = re.compile(r"^\s*(?:final\s+)?(?P<type>[A-Za-z_$][\w.$]*(?:\s*<[^;=]*>)?(?:\[\])*)\s+(?P<name>[A-Za-z_$][\w$]*)\s*(?:=|;|:)")
FOREACH = re.compile(r"for\s*\(\s*(?:final\s+)?(?P<type>[A-Za-z_$][\w.$]*(?:<[^)]*>)?(?:\[\])*)\s+(?P<name>[A-Za-z_$][\w$]*)\s*:")
INSTOF = re.compile(r"instanceof\s+(?P<type>[A-Za-z_$][\w.$]*(?:<[^)]*>)?)\s+(?P<name>[A-Za-z_$][\w$]*)")

def simple(t):
    t = t.split("<")[0].replace("[]", "")
    return t.split(".")[-1]

PARAM = re.compile(r"^[\w.$<>\[\],?\s]+\s+[A-Za-z_$][\w$]*$")


CONTROL = re.compile(r"^\s*(?:\}\s*)?(?:if|while|for|switch|catch|do|else|try|return|synchronized|new|case)\b")


def _is_signature(params, line=""):
    """Statements such as `if (!new Foo(bar).callEvent()) {` or
    `if (be instanceof PistonMovingBlockEntity) {` also match SIG; a real
    parameter list is a comma-separated run of `Type name` pairs, and a real
    signature does not start with a control keyword."""
    if line and CONTROL.match(line):
        return False
    params = params.strip()
    if not params:
        return True
    for p in re.split(r",(?![^<(]*[>)])", params):
        p = p.strip().replace("final ", "")
        if not p or not PARAM.match(p):
            return False
    return True


def logical_lines(lines):
    """[(first_line_index, joined_text)] - the decompiler wraps long method
    signatures across several lines, so join until parentheses balance."""
    out, i, n = [], 0, len(lines)
    while i < n:
        depth = lines[i].count("(") - lines[i].count(")")
        j, text = i, lines[i]
        while depth > 0 and j + 1 < n:
            j += 1
            text += " " + lines[j].strip()
            depth += lines[j].count("(") - lines[j].count(")")
        out.append((i, text))
        i += 1
    return out


def candidates(lines, idx, logical=None):
    """(name, type) visible above `idx`, from the innermost enclosing method."""
    logical = logical if logical is not None else logical_lines(lines)
    start, params = None, None
    for k in range(idx - 1, -1, -1):
        m = SIG.match(logical[k][1])
        if m and _is_signature(m.group("params"), logical[k][1]):
            start, params = k, m.group("params")
            break
    if start is None:
        return []
    out = []
    for p in re.split(r",(?![^<(]*[>)])", params):
        p = p.strip().replace("final ", "")
        if not p:
            continue
        bits = p.rsplit(" ", 1)
        if len(bits) == 2:
            out.append((bits[1].strip(), bits[0].strip()))
    for k in range(start, idx):
        for rx in (DECL, FOREACH, INSTOF):
            for m in rx.finditer(lines[k]):
                out.append((m.group("name"), m.group("type")))
    # lambda parameters of any lambda that encloses the failing line
    for k in range(start, idx + 1):
        for m in re.finditer(r"\(([^()]*)\)\s*->", lines[k]):
            for p in m.group(1).split(","):
                p = p.strip()
                if re.fullmatch(r"[A-Za-z_$][\w$]*", p):
                    out.append((p, ""))
    return out


# Paper's source names parameters after their type; the decompile does not.
ALIASES = {
    "level": ("Level", "ServerLevel", "LevelAccessor", "ServerLevelAccessor", "WorldGenLevel", "LevelReader"),
    "serverLevel": ("ServerLevel",),
    "world": ("Level", "ServerLevel"),
    "pos": ("BlockPos",),
    "blockPos": ("BlockPos",),
    "clickedPos": ("BlockPos",),
    "state": ("BlockState",),
    "blockState": ("BlockState",),
    "stack": ("ItemStack",),
    "itemStack": ("ItemStack",),
    "item": ("ItemStack",),
    "entity": ("Entity", "LivingEntity", "Mob"),
    "livingEntity": ("LivingEntity",),
    "player": ("Player", "ServerPlayer"),
    "direction": ("Direction",),
    "hand": ("InteractionHand",),
    "random": ("RandomSource",),
    "source": ("DamageSource",),
    "damageSource": ("DamageSource",),
    "tag": ("CompoundTag",),
    "input": ("ValueInput",),
    "output": ("ValueOutput",),
    "buffer": ("FriendlyByteBuf", "RegistryFriendlyByteBuf"),
    "server": ("MinecraftServer",),
    "container": ("Container",),
    "inventory": ("Container", "Inventory"),
    "blockEntity": ("BlockEntity",),
}


def pick(sym, cands):
    s = sym.lower()
    exact = [n for n, t in cands if simple(t).lower() == s]
    if len(set(exact)) == 1:
        return exact[0]
    ends = [n for n, t in cands if simple(t).lower().endswith(s)]
    if len(set(ends)) == 1:
        return ends[0]
    starts = [n for n, t in cands if s.startswith(simple(t).lower()) or simple(t).lower().startswith(s)]
    if len(set(starts)) == 1:
        return starts[0]
    for wanted in ALIASES.get(sym, ()):                 # exact type from the alias table
        hit = [n for n, t in cands if simple(t) == wanted]
        if len(set(hit)) == 1:
            return hit[0]
    alias = [n for n, t in cands if simple(t) in ALIASES.get(sym, ())]
    if len(set(alias)) == 1:
        return alias[0]
    return None

def main(logfile, apply_changes):
    log = open(logfile, errors="replace").read().splitlines()
    items = set()
    for i, ln in enumerate(log):
        sym = None
        m = re.match(r"\s*symbol:\s+variable ([A-Za-z_$][\w$]*)$", ln)
        if m:
            sym = m.group(1)
        else:
            # `level.spigotConfig` in a static method resolves to the inherited
            # BlockEntity#level field, and `serverLevel.purpurConfig` to a package,
            # so javac reports these two shapes rather than an unknown symbol.
            m = re.search(r"error: non-static variable ([A-Za-z_$][\w$]*) cannot be referenced from a static context$", ln)
            if not m:
                m = re.search(r"error: package ([A-Za-z_$][\w$]*) does not exist$", ln)
            if m:
                sym = m.group(1)
                m2 = re.search(r"(\S+\.java):(\d+): error:", ln)
                if m2:
                    items.add((m2.group(1), int(m2.group(2)), sym))
                continue
        if sym is None:
            continue
        for j in range(i - 1, max(-1, i - 6), -1):
            m2 = re.search(r"(\S+\.java):(\d+): error:", log[j])
            if m2:
                items.add((m2.group(1), int(m2.group(2)), sym))
                break
    byfile = collections.defaultdict(list)
    for f, l, s in items:
        byfile[f].append((l, s))
    fixed, unresolved = 0, []
    for f, entries in sorted(byfile.items()):
        lines = open(f, encoding="utf-8").read().split("\n")
        changed = False
        for l, sym in sorted(set(entries)):
            idx = l - 1
            if idx >= len(lines) or sym not in lines[idx]:
                unresolved.append((f, l, sym, "line moved")); continue
            target = pick(sym, candidates(lines, idx))
            if target is None or target == sym:
                unresolved.append((f, l, sym, "no unique candidate")); continue
            new = rename(lines[idx], {sym: target})
            if new == lines[idx]:
                unresolved.append((f, l, sym, "rename no-op")); continue
            lines[idx] = new
            changed = True
            fixed += 1
        if changed and apply_changes:
            open(f, "w", encoding="utf-8").write("\n".join(lines))
    print("resolved:", fixed, " unresolved:", len(unresolved))
    with open("resolve_unresolved.txt", "w") as fh:
        for u in unresolved:
            fh.write(f"{u[0]}:{u[1]} {u[2]} ({u[3]})\n")

main(sys.argv[1], len(sys.argv) > 2 and sys.argv[2] == "write")
