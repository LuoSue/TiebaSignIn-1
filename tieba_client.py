import hashlib
import logging
import random
import time
from typing import Optional

import requests

logger = logging.getLogger(__name__)

# ---------- constants ----------
SIGN_KEY = "tiebaclient!!!"
TBS_URL = "http://tieba.baidu.com/dc/common/tbs"
LIKE_URL = "http://c.tieba.baidu.com/c/f/forum/like"
SIGN_URL = "http://c.tieba.baidu.com/c/c/forum/sign"

HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/95.0.4638.69 Safari/537.36"
    ),
}

BASE_SIGN_DATA = {
    "_client_type": "2",
    "_client_version": "9.7.8.0",
    "_phone_imei": "000000000000000",
    "model": "MI+5",
    "net_type": "1",
}


# ---------- client ----------
class TiebaClient:
    """
    百度贴吧签到客户端
    """

    def __init__(self, bduss: str) -> None:
        if not bduss:
            raise ValueError("BDUSS 不能为空")
        self.bduss = bduss
        self._session: Optional[requests.Session] = None

    # -- session 惰性初始化 --
    @property
    def session(self) -> requests.Session:
        if self._session is None:
            self._session = requests.Session()
            self._session.headers.update(HEADERS)
            # 将 BDUSS 注入 Cookie，否则服务端收不到认证信息
            requests.utils.add_dict_to_cookiejar(
                self._session.cookies, {"BDUSS": self.bduss}
            )
        return self._session

    # -- 签名算法 --
    @staticmethod
    def signature(data: dict) -> str:
        s = "".join(f"{k}={data[k]}" for k in sorted(data))
        return hashlib.md5((s + SIGN_KEY).encode()).hexdigest().upper()

    # -- 带指数退避的请求 --
    def _request(
        self,
        url: str,
        method: str = "get",
        data: Optional[dict] = None,
        retry: int = 3,
    ) -> Optional[dict]:
        for i in range(retry):
            try:
                if method.lower() == "get":
                    resp = self.session.get(url, timeout=10)
                else:
                    resp = self.session.post(url, data=data, timeout=10)

                resp.raise_for_status()
                if not resp.text.strip():
                    raise ValueError("空响应")
                return resp.json()
            except Exception as e:
                if i == retry - 1:
                    logger.error(f"请求失败(已重试 {retry} 次): {e}")
                    return None
                wait = 1.5 * (2**i) + random.uniform(0, 1)
                logger.warning(f"请求异常，{wait:.1f}s 后重试 ({i+1}/{retry}): {e}")
                time.sleep(wait)
        return None

    # -- 获取 tbs --
    def get_tbs(self) -> Optional[str]:
        """获取 tbs，BDUSS 有效性由后续签到请求自然验证。"""
        result = self._request(TBS_URL)
        if result is None:
            logger.error("获取 tbs 失败")
            return None
        return result.get("tbs", "")

    # -- 获取关注的贴吧列表 --
    def get_favorites(self) -> list[dict]:
        forums: list[dict] = []
        page_no = 1

        while True:
            data = {
                "BDUSS": self.bduss,
                "_client_type": "2",
                "_client_id": "wappc_1534235498291_488",
                "_client_version": "9.7.8.0",
                "_phone_imei": "000000000000000",
                "from": "1008621y",
                "page_no": str(page_no),
                "page_size": "200",
                "model": "MI+5",
                "net_type": "1",
                "timestamp": str(int(time.time())),
                "vcode_tag": "11",
            }
            data["sign"] = self.signature(data)

            result = self._request(LIKE_URL, "post", data)
            if result is None:
                logger.warning("获取贴吧列表失败，停止翻页")
                break

            if "forum_list" in result:
                for forum_type in ("non-gconforum", "gconforum"):
                    items = result["forum_list"].get(forum_type, [])
                    if isinstance(items, list):
                        forums.extend(items)
                    elif isinstance(items, dict):
                        forums.append(items)

            if result.get("has_more") != "1":
                break

            page_no += 1
            time.sleep(random.uniform(1, 2))

        logger.info(f"共获取到 {len(forums)} 个关注的贴吧")
        return forums

    # -- 单个贴吧签到 --
    def sign_forum(self, fid: str, name: str, tbs: str) -> dict:
        """
        返回 {
            "status": "success" | "exist" | "shield" | "error",
            "rank": Optional[int],   -- 签到排名 (仅 success)
            "message": str,
        }
        """
        data = {**BASE_SIGN_DATA}
        data.update(
            {
                "BDUSS": self.bduss,
                "fid": fid,
                "kw": name,
                "tbs": tbs,
                "timestamp": str(int(time.time())),
            }
        )
        data["sign"] = self.signature(data)

        result = self._request(SIGN_URL, "post", data)
        if result is None:
            return {"status": "error", "rank": None, "message": "网络请求失败"}

        error_code = result.get("error_code", "")
        error_msg = result.get("error_msg", "")

        if error_code == "0":
            rank = None
            if "user_info" in result:
                rank = result["user_info"].get("user_sign_rank")
                rank = int(rank) if rank else None
            return {"status": "success", "rank": rank, "message": "签到成功"}
        elif error_code == "160002":
            return {"status": "exist", "rank": None, "message": error_msg or "今日已签到"}
        elif error_code == "340006":
            return {"status": "shield", "rank": None, "message": "贴吧已被屏蔽"}
        else:
            return {"status": "error", "rank": None, "message": error_msg or "未知错误"}
