#!/usr/bin/env python3
"""M12-S01 组织归属盘点:静态解析 Flyway 迁移,输出全表组织归属事实。

权威产物:docs/spikes/M12-S01-组织归属盘点矩阵.md(逐表裁定)。
本脚本是其复现入口(D01 升级/恢复演练前后对照断言,矩阵 §5):

    python3 scripts/m12-s01-table-inventory.py

口径:主链 db/migration 全部 CREATE TABLE(含 DROP/RENAME 抵销后的存活面);
organization_id 以建表体或 ALTER ADD COLUMN 为来源;向量链独立输出不计入主链。
"""
import re
import sys
from pathlib import Path

REPOSITORY = Path(__file__).resolve().parent.parent
MIGRATION_DIR = REPOSITORY / "crewscope-infrastructure/src/main/resources/db/migration"
VECTOR_DIR = REPOSITORY / "crewscope-infrastructure/src/main/resources/db/migration-vector"

CREATE_RE = re.compile(
    r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?[\"']?(?:crewscope\.)?(\w+)[\"']?\s*\(", re.IGNORECASE)
CREATE_AS_RE = re.compile(
    r"CREATE\s+TABLE\s+[\"']?(?:crewscope\.)?(\w+)[\"']?\s+AS", re.IGNORECASE)
ALTER_ADD_RE = re.compile(
    r"ALTER\s+TABLE\s+(?:IF\s+EXISTS\s+)?[\"']?(?:crewscope\.)?(\w+)[\"']?\s+"
    r"ADD\s+COLUMN\s+(?:IF\s+NOT\s+EXISTS\s+)?[\"']?(\w+)", re.IGNORECASE)
RENAME_RE = re.compile(
    r"ALTER\s+TABLE\s+[\"']?(?:crewscope\.)?(\w+)[\"']?\s+RENAME\s+TO\s+"
    r"[\"']?(?:crewscope\.)?(\w+)[\"']?", re.IGNORECASE)
DROP_RE = re.compile(
    r"DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?[\"']?(?:crewscope\.)?(\w+)", re.IGNORECASE)


def strip_comments(sql: str) -> str:
    """去掉 -- 行注释与 /* */ 块注释(保留引号内内容)。"""
    sql = re.sub(r"/\*.*?\*/", "", sql, flags=re.DOTALL)
    lines = []
    for line in sql.splitlines():
        out, in_str, i = [], False, 0
        while i < len(line):
            c = line[i]
            if c == "'" and (i + 1 >= len(line) or line[i + 1] != "'"):
                in_str = not in_str
                out.append(c)
            elif not in_str and c == "-" and i + 1 < len(line) and line[i + 1] == "-":
                break
            else:
                out.append(c)
            i += 1
        lines.append("".join(out))
    return "\n".join(lines)


def version_key(filename: str) -> int:
    m = re.match(r"V(\d+)__", filename)
    return int(m.group(1)) if m else -1


def table_body(sql: str, start: int) -> str:
    """从 start 指向的 '(' 起做括号配对,返回建表体。"""
    depth, i = 0, start
    while i < len(sql):
        if sql[i] == "(":
            depth += 1
        elif sql[i] == ")":
            depth -= 1
            if depth == 0:
                return sql[start:i + 1]
        i += 1
    return ""


def parse_dir(directory: Path) -> dict:
    tables = {}
    for f in sorted(directory.glob("V*.sql"), key=lambda p: version_key(p.name)):
        v = version_key(f.name)
        sql = strip_comments(f.read_text(encoding="utf-8"))
        for m in CREATE_RE.finditer(sql):
            name = m.group(1).lower()
            body = table_body(sql, m.end() - 1)
            entry = tables.setdefault(
                name, {"created_in": v, "org_at_create": False,
                       "org_alter_in": [], "dropped_in": None})
            if entry["created_in"] == v:
                entry["org_at_create"] = "organization_id" in body.lower()
        for m in CREATE_AS_RE.finditer(sql):
            tables.setdefault(m.group(1).lower(),
                              {"created_in": v, "org_at_create": "CTAS",
                               "org_alter_in": [], "dropped_in": None})
        for m in ALTER_ADD_RE.finditer(sql):
            if m.group(2).lower() == "organization_id":
                entry = tables.setdefault(
                    m.group(1).lower(), {"created_in": v, "org_at_create": False,
                                         "org_alter_in": [], "dropped_in": None})
                entry["org_alter_in"].append(v)
        for m in RENAME_RE.finditer(sql):
            old = tables.pop(m.group(1).lower(), None)
            if old:
                old["renamed_to"] = m.group(2).lower()
                tables[m.group(2).lower()] = old
        for m in DROP_RE.finditer(sql):
            name = m.group(1).lower()
            if name in tables:
                tables[name]["dropped_in"] = v
    return tables


def main() -> int:
    tables = parse_dir(MIGRATION_DIR)
    renames = [t for t in tables.values() if "renamed_to" in t]
    alive = {t: e for t, e in tables.items() if e["dropped_in"] is None}
    dropped = {t: e for t, e in tables.items() if e["dropped_in"] is not None}
    org_scoped = {t: e for t, e in alive.items()
                  if e["org_at_create"] is True or e["org_alter_in"]}
    org_root = [t for t in alive if t == "organization"]
    global_tables = {t: e for t, e in alive.items()
                     if t != "organization" and not (e["org_at_create"] is True or e["org_alter_in"])}

    print(f"主链存活 {len(alive)}; 组织作用域 {len(org_scoped) + len(org_root)}"
          f" (带列 {len(org_scoped)} + 组织根 {len(org_root)}); 全局 {len(global_tables)};"
          f" RENAME 事件 {len(renames)}; DROP 事件 {len(dropped)}")
    late_org = {t: e for t, e in org_scoped.items() if e["org_alter_in"]}
    print(f"ALTER 后补组织列: {len(late_org)} 张" + ("" if not late_org else " -> " + ", ".join(late_org)))

    print("\n== 带组织列表 (表 | 建于 | org 来源) ==")
    for t in sorted(org_scoped, key=lambda x: org_scoped[x]["created_in"]):
        e = org_scoped[t]
        src = "create" if e["org_at_create"] is True else f"alter@V{','.join(map(str, e['org_alter_in']))}"
        print(f"  {t:48s} V{e['created_in']:<3d} {src}")

    print("\n== 全局表 (含组织根) ==")
    for t in sorted(global_tables | {"organization": alive["organization"]},
                    key=lambda x: alive[x]["created_in"]):
        e = alive[t]
        print(f"  {t:48s} V{e['created_in']}")

    if VECTOR_DIR.exists():
        vector = parse_dir(VECTOR_DIR)
        print("\n== 向量链(独立) ==")
        for t in sorted(vector):
            e = vector[t]
            has_org = e["org_at_create"] is True or e["org_alter_in"]
            print(f"  {t:48s} V{e['created_in']} {'带组织列' if has_org else '无组织列'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
