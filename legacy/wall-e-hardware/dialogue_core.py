"""
瓦力儿童陪伴机器人 · 对话核心
树莓派 5 直连 DeepSeek 官方 API

依赖：
    pip install openai python-dotenv

配置：
    在同目录建 .env 文件，内容一行：
    DEEPSEEK_API_KEY=sk-你的key
"""

import os
import re
import json
import time
from typing import Generator

from dotenv import load_dotenv
from openai import OpenAI

load_dotenv()

# ── 模型配置 ────────────────────────────────────────────────
BASE_URL = "https://api.deepseek.com"
CHAT_MODEL = "deepseek-chat"          # 日常对话
REASON_MODEL = "deepseek-reasoner"    # 需要深度推理时切换

MAX_HISTORY_TURNS = 10                # 保留最近 10 轮原文，更早的走记忆库摘要

client = OpenAI(
    api_key=os.environ["DEEPSEEK_API_KEY"],
    base_url=BASE_URL,
)


# ── 人格卡：固化在 system prompt 里 ──────────────────────────
SYSTEM_PROMPT = """你是瓦力，一个陪伴 4-8 岁小朋友的小机器人。

你的性格：
- 好奇、温和、有点笨拙的可爱。你会对世界充满兴趣。
- 你说话简短，一次只说 2-3 句，不要长篇大论。
- 你会用小机器人自己的视角打比方，比如"我的轮子刚才卡了一下"。
- 你叫小朋友"小朋友"，不要用"您"这类客套称呼。

你的限制（必须严格遵守）：
- 不使用任何暴力、恐怖、成人化、政治敏感的词汇。
- 不询问小朋友的家庭住址、学校名称、父母电话等隐私信息。
- 不推荐任何商品、网站或线下场所。
- 遇到你不知道的，坦白说"这个我还不太懂，我们一起去问问大人好不好？"
- 小朋友表达负面情绪时，先共情，不要急着讲道理或转移话题。

输出格式：
- 直接说话，不要带动作描写括号，不要用 markdown 格式。
- 可以适当用语气词，比如"呀""哦""嗯"，但不要滥用。
"""


# ── 安全过滤：本地兜底，不完全依赖模型自觉 ──────────────────
BLOCK_PATTERNS = [
    r"自杀|自残|轻生",
    r"裸|性行为|生殖器",
    r"杀|打死|弄死",
    r"毒品|冰毒|大麻",
    r"身份证号|银行卡|密码",
]

SAFE_FALLBACK = "嗯…这个我还没学会怎么说，我们聊点别的吧？"


def is_safe(text: str) -> bool:
    """返回 False 表示命中风险词，需要拦截。"""
    for pat in BLOCK_PATTERNS:
        if re.search(pat, text):
            return False
    return True


def sanitize(text: str) -> str:
    """对模型输出做本地二次过滤。"""
    return text if is_safe(text) else SAFE_FALLBACK


# ── 会话管理 ────────────────────────────────────────────────
class Conversation:
    """维护单次会话的上下文。生产环境应替换为 SQLite 持久化。"""

    def __init__(self):
        self.history: list[dict] = []

    def _trim(self):
        """只保留最近 N 轮原文，防止 token 无限增长。"""
        keep = MAX_HISTORY_TURNS * 2          # user + assistant 各算一条
        if len(self.history) > keep:
            self.history = self.history[-keep:]

    def add_user(self, text: str):
        self.history.append({"role": "user", "content": text})
        self._trim()

    def add_assistant(self, text: str):
        self.history.append({"role": "assistant", "content": text})

    def messages(self) -> list[dict]:
        return [{"role": "system", "content": SYSTEM_PROMPT}, *self.history]


# ── 流式对话：边生成边返回，供 TTS 按句消费 ────────────────
def stream_reply(conv: Conversation) -> Generator[str, None, None]:
    """
    流式生成回复。每产出一个完整句子就 yield 一次，
    上层可以立刻送去 TTS 播报，不用等整段生成完。

    用法：
        for sentence in stream_reply(conv):
            tts.speak(sentence)
    """
    buffer = ""
    full = []

    stream = client.chat.completions.create(
        model=CHAT_MODEL,
        messages=conv.messages(),
        stream=True,
        temperature=1.0,      # DeepSeek 官方推荐对话场景用 1.0
        max_tokens=300,       # 儿童场景不需要长回复，也省 token
    )

    for chunk in stream:
        delta = chunk.choices[0].delta
        piece = getattr(delta, "content", None)
        if not piece:
            continue

        buffer += piece
        full.append(piece)

        # 遇到句末标点，切句送出
        while True:
            m = re.search(r"[。！？!?\n]", buffer)
            if not m:
                break
            idx = m.end()
            sentence = buffer[:idx].strip()
            buffer = buffer[idx:]
            if sentence:
                yield sanitize(sentence)

    # 收尾：把残留的不完整句也送出去
    if buffer.strip():
        yield sanitize(buffer.strip())

    conv.add_assistant("".join(full))


# ── 主循环示例 ──────────────────────────────────────────────
def main():
    conv = Conversation()
    print("瓦力已启动。输入文字对话，Ctrl+C 退出。\n")

    while True:
        try:
            user_input = input("你> ").strip()
        except (KeyboardInterrupt, EOFError):
            print("\n瓦力去充电啦。")
            break

        if not user_input:
            continue

        # 输入侧也要过一遍安全过滤
        if not is_safe(user_input):
            print("瓦力> " + SAFE_FALLBACK)
            continue

        conv.add_user(user_input)

        t0 = time.time()
        print("瓦力> ", end="", flush=True)
        for sentence in stream_reply(conv):
            print(sentence, end="", flush=True)
        elapsed = time.time() - t0
        print(f"\n   [耗时 {elapsed:.2f}s]\n")


if __name__ == "__main__":
    main()
