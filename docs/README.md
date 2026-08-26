# agent-platform 设计文档索引

> 治理规范：本项目实行**设计先行** —— 新建表结构、接口契约、架构决策等重要设计，
> 必须先写设计文档、经用户检查通过后才能编码。详见 [`DESIGN-DOCUMENT-POLICY.md`](./DESIGN-DOCUMENT-POLICY.md)。

## 目录结构

```
docs/
├── DESIGN-DOCUMENT-POLICY.md      # 设计文档治理规范（为什么、范围、流程）
├── templates/
│   └── TABLE-DESIGN-TEMPLATE.md   # 表结构设计模板（含逐项填写说明）
└── design/                        # 所有设计文档产出（按类别分目录）
    ├── table/                     # 表结构设计：YYYYMMDD-<表名>.md
    ├── api/                       # 接口契约
    ├── adr/                       # 架构决策记录
    └── security/                  # 安全设计
```

## 快速指引

| 你要做的事 | 去哪里 |
|-----------|--------|
| 了解为什么必须先写设计文档 | `DESIGN-DOCUMENT-POLICY.md` |
| 新建一张表 | 复制 `templates/TABLE-DESIGN-TEMPLATE.md` → 填入 `design/table/` → 提交用户检查 |
| 检查现有设计 | `design/` 下按类别浏览，每份文档第 10 节有检查记录 |
