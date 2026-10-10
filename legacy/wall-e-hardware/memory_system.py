"""
瓦力 · 记忆系统
三层结构：短期缓冲 → 每日摘要 → 长期画像

设计原则：
  1. 不是所有对话都值得记。"什么该忘"比"什么该记"更重要。
  2. 短期保原文，中期做摘要，长期只留结构化事实。
  3. 记忆注入是有限额的——每轮只带最相关的几条，防止 prompt 膨胀。
"""

import json
import sqlite3
import time
from dataclasses import dataclass, asdict
from pathlib import Path


DB_PATH = Path(__file__).parent / "walle_memory.db"


# ── 数据模型 ────────────────────────────────────────────────
@dataclass
class MemoryItem:
    """一条长期记忆。只存结构化事实，不存原始对话。"""
    category: str      # 人物 / 喜好 / 事件 / 习惯
    content: str       # "小朋友最喜欢恐龙，尤其是霸王龙"
    importance: int    # 1-5，5 最重要。只有 >=3 才会被主动召回
    created_at: float
    last_used: float


def init_db():
    conn = sqlite3.connect(DB_PATH)
    conn.executescript("""
        CREATE TABLE IF NOT EXISTS long_term (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            category    TEXT NOT NULL,
            content     TEXT NOT NULL,
            importance  INTEGER NOT NULL DEFAULT 1,
            created_at  REAL NOT NULL,
            last_used   REAL NOT NULL
        );

        CREATE TABLE IF NOT EXISTS daily_summary (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            date        TEXT NOT NULL UNIQUE,
            summary     TEXT NOT NULL,
            created_at  REAL NOT NULL
        );

        CREATE INDEX IF NOT EXISTS idx_lt_importance
            ON long_term(importance DESC, last_used DESC);
    """)
    conn.commit()
    return conn


# ── 长期记忆操作 ────────────────────────────────────────────
class MemoryStore:
    def __init__(self, conn: sqlite3.Connection):
        self.conn = conn

    def remember(self, category: str, content: str, importance: int = 3):
        """写入一条长期记忆。importance < 3 的建议不要写进来。"""
        now = time.time()
        self.conn.execute(
            "INSERT INTO long_term (category, content, importance, created_at, last_used)"
            " VALUES (?, ?, ?, ?, ?)",
            (category, content, importance, now, now),
        )
        self.conn.commit()

    def recall(self, limit: int = 8) -> list[MemoryItem]:
        """
        召回：按重要性 + 最近使用排序。
        真实项目里这里应该换成向量检索，用当前对话做 query。
        现在用 rule-based 排序，够用且零依赖。
        """
        rows = self.conn.execute(
            "SELECT category, content, importance, created_at, last_used"
            " FROM long_term"
            " WHERE importance >= 3"
            " ORDER BY importance DESC, last_used DESC"
            " LIMIT ?",
            (limit,),
        ).fetchall()

        items = [MemoryItem(*r) for r in rows]

        # 标记为已使用，让新鲜的记忆有机会轮换进来
        if items:
            ids = [r[0] for r in self.conn.execute(
                "SELECT id FROM long_term WHERE importance >= 3"
                " ORDER BY importance DESC, last_used DESC LIMIT ?", (limit,)
            ).fetchall()]
            self.conn.executemany(
                "UPDATE long_term SET last_used = ? WHERE id = ?",
                [(time.time(), i) for i in ids],
            )
            self.conn.commit()

        return items

    def recall_as_prompt(self, limit: int = 8) -> str:
        """把召回的长期记忆拼成一段可注入 prompt 的文本。"""
        items = self.recall(limit)
        if not items:
            return ""
        lines = ["关于这位小朋友，你记得这些事："]
        for it in items:
            lines.append(f"- [{it.category}] {it.content}")
        return "\n".join(lines)

    def forget_older_than(self, days: int, max_importance: int = 2):
        """
        遗忘机制：低重要性的旧记忆定期清理。
        这是"什么该忘"的落地——不清理，画像会被噪音淹没。
        """
        cutoff = time.time() - days * 86400
        self.conn.execute(
            "DELETE FROM long_term WHERE created_at < ? AND importance <= ?",
            (cutoff, max_importance),
        )
        self.conn.commit()

    def stats(self) -> dict:
        total = self.conn.execute("SELECT COUNT(*) FROM long_term").fetchone()[0]
        by_cat = dict(self.conn.execute(
            "SELECT category, COUNT(*) FROM long_term GROUP BY category"
        ).fetchall())
        return {"total": total, "by_category": by_cat}


# ── 每日摘要 ────────────────────────────────────────────────
def build_daily_summary(client, model: str, transcript: list[dict]) -> dict:
    """
    每天睡前把当日对话压缩成摘要，并抽取值得长期记的事实。

    transcript 形如：
        [{"role": "user", "content": "..."}, {"role": "assistant", "content": "..."}]

    返回：
        {"summary": "...", "facts": [{"category": "...", "content": "...", "importance": 3}]}
    """
    if not transcript:
        return {"summary": "", "facts": []}

    dialog_text = "\n".join(f"{t['role']}: {t['content']}" for t in transcript)

    prompt = f"""下面是一个小机器人和小朋友今天的对话记录。

请你做两件事：

1. 用两三句话总结今天聊了什么、小朋友的状态怎么样。

2. 抽取值得长期记住的事实。判断标准是：这件事下周还记得有没有用？
   不要记录临时情绪、不要记录当天吃了什么这类无意义信息。
   每条事实标注类别（人物/喜好/事件/习惯）和重要度（1-5）。
   只输出重要度 >= 3 的。

只输出 JSON，格式：
{{"summary": "摘要文字", "facts": [{{"category": "喜好", "content": "喜欢恐龙", "importance": 4}}]}}

对话记录：
{dialog_text}"""

    resp = client.chat.completions.create(
        model=model,
        messages=[{"role": "user", "content": prompt}],
        temperature=0.3,
        response_format={"type": "json_object"},
    )

    try:
        return json.loads(resp.choices[0].message.content)
    except (json.JSONDecodeError, KeyError):
        return {"summary": "", "facts": []}


def save_daily_summary(conn, date: str, summary: str):
    conn.execute(
        "INSERT OR REPLACE INTO daily_summary (date, summary, created_at)"
        " VALUES (?, ?, ?)",
        (date, summary, time.time()),
    )
    conn.commit()


# ── 演示 ────────────────────────────────────────────────────
if __name__ == "__main__":
    conn = init_db()
    store = MemoryStore(conn)

    store.remember("喜好", "最喜欢恐龙，尤其是霸王龙", importance=5)
    store.remember("习惯", "每天晚上八点会来和瓦力说话", importance=4)
    store.remember("人物", "有个妹妹叫果果，经常抢他的玩具", importance=4)
    store.remember("事件", "上周去了动物园，看到了长颈鹿", importance=3)
    store.remember("喜好", "今天中午吃了番茄炒蛋", importance=1)   # 不该被召回

    print("召回结果（注入 prompt 的内容）：")
    print(store.recall_as_prompt())
    print()
    print("记忆统计：", store.stats())
