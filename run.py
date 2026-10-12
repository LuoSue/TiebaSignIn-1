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
from datetime import datetime, timezone, timedelta

from tieba_client import TiebaClient
import wechat_notify

BJ_TZ = timezone(timedelta(hours=8))
logging.Formatter.converter = staticmethod(
    lambda s: datetime.fromtimestamp(s, BJ_TZ).timetuple()
)
logging.basicConfig(
    level=logging.INFO,
    format="[%(levelname)s] %(asctime)s: %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
logger = logging.getLogger(__name__)


def parse_args() -> str:
    parser = argparse.ArgumentParser(description="百度贴吧自动签到")
    parser.add_argument("--bduss", default=None, help="贴吧 BDUSS Cookie 值（优先级高于环境变量）")
    args = parser.parse_args()
    bduss = args.bduss or os.environ.get("BDUSS", "")
    if not bduss:
        parser.error("请通过 --bduss 参数或 BDUSS 环境变量提供 BDUSS")
    return bduss


def sign_round(
    client: TiebaClient,
    forums: list[dict],
    tbs: str,
    stats: dict,
    *,
    delay_range: tuple[float, float],
    extra_rest: tuple[float, float],
    prefix: str = "",
) -> list[dict]:
    """对 forums 执行一轮签到，返回仍然失败的贴吧列表。"""
    failed = []
    total = len(forums)
    for idx, forum in enumerate(forums):
        time.sleep(random.uniform(*delay_range))
        if (idx + 1) % 10 == 0:
            extra = random.uniform(*extra_rest)
            logger.info(f"已处理 {idx + 1}/{total} 个，休息 {extra:.1f}s ...")
            time.sleep(extra)

        fid, fname = forum.get("id", ""), forum.get("name", "")
        result = client.sign_forum(fid, fname, tbs)
        status = result["status"]
        stats[status] += 1

        tag = f"【{fname}】({prefix}{idx + 1}/{total})"
        if status == "success":
            rank = f"，第 {result['rank']} 个签到" if result.get("rank") else ""
            logger.info(f"{tag} 签到成功{rank}")
        elif status == "exist":
            logger.info(f"{tag} {result['message']}")
        elif status == "shield":
            logger.warning(f"{tag} {result['message']}")
        else:
            logger.error(f"{tag} 签到失败: {result['message']}")
            failed.append(forum)
    return failed


def build_wechat_content(total: int, stats: dict) -> str:
    date_str = datetime.now(BJ_TZ).strftime("%Y-%m-%d %H:%M:%S")
    lines = [
        "# 贴吧签到结果",
        f"> 时间：{date_str}",
        f"- 贴吧总数：**{total}**",
        f"- 签到成功：**{stats['success']}**",
        f"- 已经签到：{stats['exist']}",
        f"- 被屏蔽的：{stats['shield']}",
        f"- 签到失败：{stats['error']}",
    ]
    if stats["error"] > 0:
        lines.append("> 存在签到失败的贴吧，请查看 Actions 运行日志")
    return "\n".join(lines)


def main() -> None:
    bduss = parse_args()
    client = TiebaClient(bduss)

    # 1. 获取 tbs
    logger.info("正在获取 tbs...")
    tbs = client.get_tbs()
    if not tbs:
        logger.error("获取 tbs 失败，退出")
        raise SystemExit(1)

    # 2. 获取关注的贴吧列表
    logger.info("正在获取关注的贴吧列表...")
    forums = client.get_favorites()
    if not forums:
        logger.warning("未获取到关注的贴吧，签到结束")
        wechat_notify.send_markdown("# 贴吧签到结果\n> 未获取到关注的贴吧，签到结束")
        return

    total = len(forums)
    stats = {"success": 0, "exist": 0, "shield": 0, "error": 0}

    # 3. 逐个签到 (带节流与失败重试机制)
    # 3.1 第一轮
    logger.info(f"开始第 1 轮签到，共 {total} 个贴吧")
    failed = sign_round(
        client, forums, tbs, stats,
        delay_range=(1.0, 2.5), extra_rest=(5, 10),
    )

    # 3.2 第二轮重试
    if failed:
        logger.info(f"\n===== 第 1 轮结束，{len(failed)} 个失败，15 秒后重试 =====")
        time.sleep(15)
        if new_tbs := client.get_tbs():
            tbs = new_tbs
            logger.info("已刷新 tbs")
        # 重试成功时，把 error 挪到对应状态
        for f in failed:
            stats["error"] -= 1  # 先扣回，sign_round 会重新统计
        failed = sign_round(
            client, failed, tbs, stats,
            delay_range=(1.5, 3.0), extra_rest=(5, 8),
            prefix="重试 ",
        )

    # 4. 汇总
    summary = [
        "\n========== 签到汇总 ==========",
        f"贴吧总数: {total}",
        f"签到成功: {stats['success']}",
        f"已经签到: {stats['exist']}",
        f"被屏蔽的: {stats['shield']}",
        f"签到失败: {stats['error']}",
    ]
    if failed:
        summary.append(f"重试失败贴吧: {', '.join(f.get('name', '') for f in failed)}")
    summary.append("================================")
    logger.info("\n".join(summary))

    wechat_notify.send_markdown(build_wechat_content(total, stats))


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        wechat_notify.send_markdown(
            f"# 贴吧签到结果\n> {datetime.now(BJ_TZ)} 签到异常中断，请查看 Actions 运行日志"
        )
        raise
    except Exception as e:
        logger.exception("签到过程发生未预期异常")
        wechat_notify.send_markdown(
            f"# 贴吧签到结果\n> {datetime.now(BJ_TZ)} 签到异常：{e}"
        )
        raise