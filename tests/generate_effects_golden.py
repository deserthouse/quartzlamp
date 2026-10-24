#!/usr/bin/env python3
"""从 Android Effects.kt 解析 153 名称并按词法规则生成 windows/golden/effects.json。
Python 与 Kotlin(subCatOf)/C#(Effects.SubCatOf) 三重一致；改分类规则须三端同步重跑。"""
import io
import json
import re

SRC = 'app/src/main/java/io/github/deserthouse/quartzlamp/data/Effects.kt'
OUT = 'windows/golden/effects.json'
ATOMS = '蓝紫红黄绿青白'
DOT_PREFIXES = ['白点滚动、', '红点滚动、', '白点跑动、', '红点跑动、', '白点、', '红点、']
SUFFIXES = ['双向跳动', '跳动', '闭幕']


def sub_cat(tab, name):
    core = name
    for p in DOT_PREFIXES:
        if core.startswith(p):
            core = core[len(p):]
            break
    for s in SUFFIXES:
        if core.endswith(s):
            core = core[:-len(s)]
            break
    if core.endswith('色'):
        core = core[:-1]
    if core in ('七彩流水', '七彩'):
        base = 'RAINBOW'
    elif '底' in core:
        base = 'DUO'
    elif len(core) == 2 and all(c in ATOMS for c in core):
        base = 'MONO_GRAD' if core[1] == '白' else 'COLOR_GRAD'
    elif len(core) == 1 and core[0] in ATOMS:
        base = 'MONO'
    else:
        base = 'COLOR_GRAD'
    return 'PURE_BROKEN' if (tab == 8 and base in ('MONO', 'DUO')) else base


def main():
    src = io.open(SRC, encoding='utf-8').read()
    block = re.search(r'val effects: List<List<String>> = listOf\((.*?)\n    \)', src, re.S).group(1)
    tabs = []
    for m in re.finditer(r'listOf\(([^)]*)\)', block):
        names = re.findall(r'"([^"]+)"', m.group(1))
        assert len(names) == 17, f'tab {len(tabs)} has {len(names)}'
        tabs.append(names)
    assert len(tabs) == 9
    classes = [[sub_cat(t, n) for n in names] for t, names in enumerate(tabs)]
    counts = {}
    for t in classes:
        for c in t:
            counts[c] = counts.get(c, 0) + 1
    assert sum(counts.values()) == 153
    assert counts.get('PURE_BROKEN', 0) == 6, 'tab8 坏项应恰 6（用户真机实证）'
    data = {
        'meta': {
            'purpose': '153 灯效名称与二级分类——双端共享数据契约',
            'nameSource': SRC + '（本 JSON 由脚本解析生成）',
            'classificationRules': '词法规则（用户 2026-10-21 定）：点前缀剥离→动作后缀剥离→色尾剥离；七彩=RAINBOW；含底=DUO；双原子=MONO_GRAD(次白)/COLOR_GRAD；单原子=MONO；兜底 COLOR_GRAD；tab8 MONO/DUO→PURE_BROKEN',
            'deviceIndexFormula': 'deviceIndex = tab * 17 + position + 1（cmode 编号，1 起）',
            'generatedBy': 'tests/generate_effects_golden.py',
        },
        'tabs': tabs,
        'classification': classes,
        'counts': dict(sorted(counts.items())),
    }
    with io.open(OUT, 'w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    print('written', OUT, counts)


if __name__ == '__main__':
    main()
