"""企业微信群机器人推送

通过环境变量 WECHAT_WEBHOOK_KEY 读取群机器人 Key，把签到结果推送到企业微信群。
未配置该环境变量时，send_markdown 会直接返回 False，不影响主流程。

企业微信群机器人文档:
    https://developer.work.weixin.qq.com/document/path/91770
"""

import logging
import os

import requests

logger = logging.getLogger(__name__)

WEBHOOK_BASE = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send"

# 企业微信 markdown 消息 content 字段上限 4096 字节，这里留点余量
MAX_CONTENT_BYTES = 4000


def get_webhook_key() -> str:
    """从环境变量读取群机器人 Key（企业微信 Webhook Key）。"""
    return os.environ.get("WECHAT_WEBHOOK_KEY", "").strip()


def send_markdown(content: str, key: str | None = None) -> bool:
    """发送 markdown 消息到企业微信群。

    Args:
        content: markdown 文本（企业微信语法子集，支持 #、**加粗**、> 引用、- 列表等）。
        key: 可选的 Webhook Key；若不传则从环境变量 WECHAT_WEBHOOK_KEY 读取。

    Returns:
        是否发送成功。未配置 Key 或网络异常时返回 False。
    """
    key = (key or "").strip() or get_webhook_key()
    if not key:
        logger.info("未配置 WECHAT_WEBHOOK_KEY，跳过企业微信推送")
        return False

    # 超长截断，避免企业微信接口报错（按字节截断并尽量不截断半个中文）
    encoded = content.encode("utf-8")
    if len(encoded) > MAX_CONTENT_BYTES:
        content = encoded[:MAX_CONTENT_BYTES].decode("utf-8", "ignore")
        logger.warning("推送内容超过长度限制，已截断")

    url = f"{WEBHOOK_BASE}?key={key}"
    payload = {"msgtype": "markdown", "markdown": {"content": content}}

    try:
        resp = requests.post(url, json=payload, timeout=10)
        resp.raise_for_status()
        data = resp.json()
        if data.get("errcode", 0) != 0:
            logger.error(f"企业微信推送失败: errcode={data.get('errcode')} errmsg={data.get('errmsg')}")
            return False
        logger.info("企业微信推送成功")
        return True
    except Exception as e:
        logger.error(f"企业微信推送异常: {e}")
        return False
