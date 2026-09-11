#!/usr/bin/env python3
"""百度贴吧自动签到 - GitHub Actions 入口

用法:
    python run.py                          # 自动读取环境变量 BDUSS
    python run.py --bduss "your_bduss"     # 命令行传入 BDUSS
"""

import argparse
import logging
import os
import random
import time
from datetime import datetime

from tieba_client import TiebaClient
import wechat_notify

logging.basicConfig(
    level=logging.INFO,
    format="[%(levelname)s] %(asctime)s: %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
logger = logging.getLogger(__name__)


def parse_args() -> str:
    parser = argparse.ArgumentParser(description="百度贴吧自动签到")
    parser.add_argument(
        "--bduss",
        default=None,
        help="贴吧 BDUSS Cookie 值（优先级高于环境变量）",
    )
    args = parser.parse_args()

    bduss = args.bduss or os.environ.get("BDUSS", "")
    if not bduss:
        parser.error("请通过 --bduss 参数或 BDUSS 环境变量提供 BDUSS")
    return bduss


def main() -> None:
    bduss = parse_args()
    client = TiebaClient(bduss)

    # 1. 获取 tbs
    logger.info("正在获取 tbs...")
    tbs = client.get_tbs()
    if tbs is None:
        logger.error("获取 tbs 失败，退出")
        raise SystemExit(1)

    # 2. 获取关注的贴吧列表
    logger.info("正在获取关注的贴吧列表...")
    forums = client.get_favorites()
    if not forums:
        logger.warning("未获取到关注的贴吧，签到结束")
        wechat_notify.send_markdown("# 贴吧签到结果\n> 未获取到关注的贴吧，签到结束")
        return

    # 3. 逐个签到 (带节流)
    total = len(forums)
    logger.info(f"开始签到 {total} 个贴吧")

    stats = {"success": 0, "exist": 0, "shield": 0, "error": 0}
    for idx, forum in enumerate(forums):
        # 节流: 随机间隔 1.0-2.5 秒
        delay = random.uniform(1.0, 2.5)
        time.sleep(delay)

        # 每 10 个贴吧额外休息 5-10 秒
        if (idx + 1) % 10 == 0:
            extra = random.uniform(5, 10)
            logger.info(f"已签到 {idx + 1}/{total} 个，休息 {extra:.1f}s ...")
            time.sleep(extra)

        fid = forum.get("id", "")
        fname = forum.get("name", "")
        result = client.sign_forum(fid, fname, tbs)
        stats[result["status"]] += 1

        # 打印单条结果
        prefix = f"【{fname}】({idx + 1}/{total})"
        if result["status"] == "success":
            rank_str = f"，第 {result['rank']} 个签到" if result["rank"] else ""
            logger.info(f"{prefix} 签到成功{rank_str}")
        elif result["status"] == "exist":
            logger.info(f"{prefix} {result['message']}")
        elif result["status"] == "shield":
            logger.warning(f"{prefix} {result['message']}")
        else:
            logger.error(f"{prefix} 签到失败: {result['message']}")

    # 4. 汇总
    summary = (
        f"\n========== 签到汇总 ==========\n"
        f"贴吧总数: {total}\n"
        f"签到成功: {stats['success']}\n"
        f"已经签到: {stats['exist']}\n"
        f"被屏蔽的: {stats['shield']}\n"
        f"签到失败: {stats['error']}\n"
        f"================================"
    )
    logger.info(summary)

    # 5. 推送签到结果到企业微信群机器人（可选，未配置 WECHAT_WEBHOOK_KEY 则自动跳过）
    wechat_notify.send_markdown(_build_wechat_content(total, stats))


def _build_wechat_content(total: int, stats: dict) -> str:
    """构造推送到企业微信的 markdown 内容。"""
    date_str = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    lines = [
        "# 贴吧签到结果",
        f"> 时间：{date_str}",
        f"- 贴吧总数：**{total}**",
        f"- 签到成功：**{stats['success']}**",
        f"- 已签到：{stats['exist']}",
        f"- 被屏蔽：{stats['shield']}",
        f"- 签到失败：{stats['error']}",
    ]
    if stats["error"] > 0:
        lines.append("> 存在签到失败的贴吧，请查看 Actions 运行日志")
    return "\n".join(lines)


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        # 主动退出的错误（如 tbs 获取失败）也推一条到企业微信，便于排查
        wechat_notify.send_markdown(
            f"# 贴吧签到结果\n> {datetime.now().strftime('%Y-%m-%d %H:%M:%S')} 签到异常中断，请查看 Actions 运行日志"
        )
        raise
    except Exception as e:  # noqa: BLE001 - 兜底推送，避免静默失败
        logger.exception("签到过程发生未预期异常")
        wechat_notify.send_markdown(
            f"# 贴吧签到结果\n> {datetime.now().strftime('%Y-%m-%d %H:%M:%S')} 签到异常：{e}"
        )
        raise
