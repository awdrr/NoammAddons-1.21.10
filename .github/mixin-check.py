#!/usr/bin/env python3
"""Temporary 1.21.11 porting aid: statically checks mixin targets against the mapped Minecraft jar.

Reports missing @Inject/@Redirect/... target methods, @Shadow members, @Accessor/@Invoker targets,
@At(INVOKE/FIELD) targets, and @Inject handlers whose parameters no longer match the target.
Usage: mixin-check.py <named-minecraft.jar> <mixin source dir>
"""
import os, re, subprocess, sys
from functools import lru_cache

JAR, SRC = sys.argv[1], sys.argv[2]
PRIMS = {'Z': 'boolean', 'B': 'byte', 'C': 'char', 'S': 'short', 'I': 'int', 'J': 'long', 'F': 'float', 'D': 'double', 'V': 'void'}


@lru_cache(None)
def javap(cls):
    r = subprocess.run(['javap', '-cp', JAR, '-p', '-s', cls], capture_output=True, text=True)
    if r.returncode != 0:
        return None
    members, last = [], None
    for line in r.stdout.splitlines():
        line = line.strip()
        if line.startswith('descriptor:'):
            if last:
                members.append((last, line.split(':', 1)[1].strip()))
            last = None
        elif line.endswith(';') or line.endswith('{') or line.endswith(');'):
            last = line
    return members


def member_name(decl):
    m = re.search(r'([\w$<>]+)\(', decl)
    if m:
        return m.group(1).split('.')[-1]
    m = re.search(r'([\w$]+);$', decl)
    return m.group(1) if m else None


def desc_params(desc):
    out, i, params = [], 1, desc[1:desc.index(')')]
    j = 0
    while j < len(params):
        dims = 0
        while params[j] == '[':
            dims += 1; j += 1
        if params[j] == 'L':
            k = params.index(';', j)
            name = params[j + 1:k].split('/')[-1].replace('$', '.')
            j = k + 1
        else:
            name = PRIMS[params[j]]; j += 1
        out.append(name + '[]' * dims)
    return out


def find_methods(cls, name):
    ms = javap(cls) or []
    res = []
    for decl, desc in ms:
        n = member_name(decl)
        if '(' in decl and (n == name or (name == '<init>' and n == cls.split('.')[-1])):
            res.append(desc)
    return res


def has_field(cls, name):
    return any('(' not in d and member_name(d) == name for d, _ in (javap(cls) or []))


def simple_type(t):
    t = re.sub(r'<.*>', '', t).strip()
    t = re.sub(r'@\w+(\([^)]*\))?\s*', '', t).strip()
    return t.split('.')[-1] if '.' in t and not t[0].isupper() else t


problems = []
for root, _, files in os.walk(SRC):
    for f in files:
        if not f.endswith('.java'):
            continue
        path = os.path.join(root, f)
        src = open(path).read()
        imports = {m.group(2): m.group(1) + m.group(2) for m in re.finditer(r'^import\s+([\w.]+\.)(\w+);', src, re.M)}

        def fqn(simple):
            parts = simple.split('.')
            base = imports.get(parts[0])
            if not base:
                return None
            return base + ''.join('$' + p for p in parts[1:])

        mm = re.search(r'@Mixin\s*\(\s*(?:value\s*=\s*)?\{?([^)}]*)\}?', src)
        if not mm:
            continue
        targets = [fqn(t.strip()[:-6]) for t in mm.group(1).split(',') if t.strip().endswith('.class')]
        targets += [t.replace('/', '.') for t in re.findall(r'targets\s*=\s*"([^"]+)"', src)]
        targets = [t for t in targets if t]
        if not targets:
            continue
        rel = os.path.relpath(path, SRC)

        def check_method_target(spec, ctx):
            name = spec.split('(')[0]
            desc = spec[len(name):] if '(' in spec else None
            for t in targets:
                if javap(t) is None:
                    problems.append(f'{rel}: cannot read target class {t}'); continue
                descs = find_methods(t, name)
                if not descs:
                    problems.append(f'{rel}: {ctx} target method "{name}" not found in {t}')
                elif desc and not any(d.startswith(desc) or d == desc for d in descs):
                    problems.append(f'{rel}: {ctx} "{spec}" descriptor mismatch in {t}; available: {descs}')
            return name

        # injector annotations followed by their handler
        for m in re.finditer(r'@(Inject|Redirect|ModifyVariable|ModifyArg|ModifyArgs|ModifyConstant|ModifyExpressionValue|ModifyReturnValue|WrapOperation|WrapWithCondition|WrapMethod)\s*\((.*?)\)\s*\n\s*(?:public|private|protected)?\s*(?:static\s+)?[\w<>\[\],.? ]+\s+\w+\s*\(([^)]*)\)', src, re.S):
            kind, body, params = m.group(1), m.group(2), m.group(3)
            mspec = re.search(r'method\s*=\s*(\{[^}]*\}|"[^"]*")', body)
            specs = re.findall(r'"([^"]+)"', mspec.group(1)) if mspec else []
            for spec in specs:
                name = check_method_target(spec, '@' + kind)
                if kind == 'Inject' and '(' not in spec:
                    hp = [simple_type(p.rsplit(None, 1)[0]) for p in params.split(',') if p.strip() and '@Local' not in p and '@Share' not in p]
                    hp = [p for p in hp if p not in ('CallbackInfo', 'CallbackInfoReturnable')]
                    if hp:
                        for t in targets:
                            descs = find_methods(t, name)
                            if descs and not any(desc_params(d) == hp for d in descs):
                                problems.append(f'{rel}: @Inject handler params {hp} match no overload of {t}.{name}: {[desc_params(d) for d in descs]}')
            for at in re.findall(r'target\s*=\s*"(L[^"]+)"', body):
                mt = re.match(r'L([\w/$]+);([\w<>$]+)(\(.*)?$', at) or re.match(r'L([\w/$]+);([\w$]+):(.*)$', at)
                if not mt:
                    continue
                owner, name, rest = mt.group(1).replace('/', '.'), mt.group(2), mt.group(3)
                if javap(owner) is None:
                    continue
                if rest and rest.startswith('('):
                    descs = find_methods(owner, name)
                    if rest not in descs:
                        problems.append(f'{rel}: @At target {owner}.{name}{rest} not found; available: {descs}')
                elif not has_field(owner, name):
                    problems.append(f'{rel}: @At field target {owner}.{name} not found')

        for m in re.finditer(r'@Shadow\b', src):
            decl = src[m.end():]
            decl = decl[:min(i for i in (decl.find(';'), decl.find('{'), len(decl)) if i >= 0)]
            decl = re.sub(r'@\w+(\([^)]*\))?', ' ', decl)
            is_method = '(' in decl
            head = decl.split('(')[0] if is_method else decl.split('=')[0]
            ids = re.findall(r'[\w$]+', head)
            if not ids:
                continue
            name = ids[-1]
            for t in targets:
                if javap(t) is None:
                    continue
                ok = bool(find_methods(t, name)) if is_method else has_field(t, name)
                if not ok:
                    problems.append(f'{rel}: @Shadow {"method" if is_method else "field"} "{name}" not found in {t}')

        for m in re.finditer(r'@(Accessor|Invoker)(?:\s*\(\s*(?:value\s*=\s*)?"([^"]+)"[^)]*\))?\s*\n?\s*(?:static\s+)?[\w<>\[\],.? ]+\s+(\w+)\s*\(', src):
            kind, explicit, method = m.groups()
            name = explicit or re.sub(r'^(get|set|is|invoke|call)', '', method)
            if not explicit:
                name = name[:1].lower() + name[1:]
            for t in targets:
                if javap(t) is None:
                    continue
                ok = has_field(t, name) if kind == 'Accessor' else bool(find_methods(t, name))
                if not ok:
                    problems.append(f'{rel}: @{kind} target "{name}" not found in {t}')

print(f'mixin-check: {len(problems)} problem(s)')
for p in problems:
    print('  ' + p)
