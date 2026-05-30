package top.srcrs;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.srcrs.domain.Cookie;
import top.srcrs.util.Encryption;
import top.srcrs.util.Request;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 程序运行开始的地方
 *
 * @author srcrs
 * @Time 2020-10-31
 */
public class Run {
    /**
     * 获取日志记录器对象
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(Run.class);

    /**
     * 获取用户所有关注贴吧 - PC端接口，支持分页
     */
    String LIKE_URL = "https://tieba.baidu.com/favForum";
    /**
     * 获取用户所有关注贴吧 - 移动端API接口
     */
    String LIKE_URL_MOBILE = "http://c.tieba.baidu.com/c/f/forum/like";
    /**
     * 获取用户的tbs
     */
    String TBS_URL = "http://tieba.baidu.com/dc/common/tbs";
    /**
     * 贴吧签到接口
     */
    String SIGN_URL = "http://c.tieba.baidu.com/c/c/forum/sign";

    /** 贴吧客户端固定参数 */
    private static final Map<String, String> CLIENT_PARAMS = new LinkedHashMap<>();
    static {
        CLIENT_PARAMS.put("_client_type", "2");
        CLIENT_PARAMS.put("_client_version", "9.7.8.0");
        CLIENT_PARAMS.put("_phone_imei", "000000000000000");
        CLIENT_PARAMS.put("model", "MI+5");
        CLIENT_PARAMS.put("net_type", "1");
    }

    /**
     * 贴吧信息内部类，存储贴吧名称和ID
     */
    private static class ForumInfo {
        final String name;
        final String id;
        ForumInfo(String name, String id) {
            this.name = name;
            this.id = id;
        }
    }

    /**
     * 存储用户所关注的贴吧（带ID和名称）
     */
    private List<ForumInfo> forums = new ArrayList<>();
    /**
     * 仅存储贴吧名称（PC端fallback，无ID时使用）
     */
    private List<String> followNames = new ArrayList<>();

    /**
     * 用户的tbs
     */
    private String tbs = "";
    /**
     * 用户所关注的贴吧数量
     */
    private static Integer followNum = 0;
    /**
     * 签到成功计数
     */
    private int successCount = 0;
    /**
     * 已签到计数
     */
    private int alreadyCount = 0;
    /**
     * 被屏蔽的贴吧计数
     */
    private int blockedCount = 0;
    /**
     * 签到失败计数
     */
    private int errorCount = 0;

    public static void main(String[] args) {
        Cookie cookie = Cookie.getInstance();
        // 存入Cookie，以备使用
        if (args.length == 0) {
            LOGGER.warn("请在Secrets中填写BDUSS");
        }
        cookie.setBDUSS(args[0]);
        Run run = new Run();
        run.getTbs();
        run.getFollow();
        run.runSign();
        LOGGER.info("共 {} 个贴吧 - 成功: {} - 已签到: {} - 屏蔽: {} - 失败: {}",
                followNum, run.successCount, run.alreadyCount, run.blockedCount, run.errorCount);
        if (args.length == 2) {
            run.send(args[1]);
        }
    }

    /**
     * 进行登录，获得 tbs ，签到的时候需要用到这个参数
     *
     * @author srcrs
     * @Time 2020-10-31
     */
    public void getTbs() {
        try {
            JSONObject jsonObject = Request.get(TBS_URL);
            if ("1".equals(jsonObject.getString("is_login"))) {
                LOGGER.info("获取tbs成功");
                tbs = jsonObject.getString("tbs");
            } else {
                LOGGER.warn("获取tbs失败 -- " + jsonObject);
            }
        } catch (Exception e) {
            LOGGER.error("获取tbs部分出现错误 -- " + e);
        }
    }

    /**
     * 获取用户所关注的贴吧列表 - 尝试PC端 → 移动端 → 后备
     *
     * @author srcrs
     * @Time 2020-10-31
     */
    public void getFollow() {
        boolean hasForums = false;

        // 阶段1: PC端分页获取
        LOGGER.info("===== 阶段1: 尝试PC端获取贴吧列表 =====");
        try {
            int page = 1;
            int perPage = 50;
            boolean hasMore = true;

            while (hasMore) {
                String pageUrl = LIKE_URL + "?pn=" + page + "&rn=" + perPage;
                JSONObject jsonObject = Request.get(pageUrl);
                LOGGER.info("PC端获取第 {} 页贴吧列表", page);

                JSONArray jsonArray = null;
                try {
                    jsonArray = jsonObject.getJSONObject("data").getJSONArray("thread_list");
                } catch (Exception e) {
                    try {
                        jsonArray = jsonObject.getJSONObject("data").getJSONArray("forum_list");
                    } catch (Exception e2) {
                        jsonArray = jsonObject.getJSONObject("data").getJSONArray("like_forum");
                    }
                }

                if (jsonArray == null || jsonArray.isEmpty()) {
                    hasMore = false;
                    break;
                }

                for (Object array : jsonArray) {
                    JSONObject obj = (JSONObject) array;
                    String tiebaName = null;
                    try {
                        tiebaName = obj.getString("forum_name");
                    } catch (Exception e) {
                        tiebaName = obj.getString("name");
                    }
                    if (tiebaName == null) continue;

                    String forumId = null;
                    try {
                        forumId = obj.getString("id");
                    } catch (Exception ignored) {}
                    try {
                        if (forumId == null) {
                            forumId = obj.getString("forum_id");
                        }
                    } catch (Exception ignored) {}

                    String isSign = "0";
                    try {
                        isSign = obj.getString("is_sign");
                    } catch (Exception ignored) {}

                    // 检查贴吧是否存在
                    if (Request.isTiebaNotExist(tiebaName)) {
                        LOGGER.info("贴吧不存在，跳过: {}", tiebaName);
                        continue;
                    }

                    if ("0".equals(isSign)) {
                        forums.add(new ForumInfo(tiebaName, forumId));
                    } else {
                        alreadyCount++;
                        LOGGER.info("PC端: 【{}】今日已签到", tiebaName);
                    }
                }

                if (jsonArray.size() < perPage) {
                    hasMore = false;
                } else {
                    page++;
                    Thread.sleep(500);
                }
            }

            if (!forums.isEmpty()) {
                followNum = forums.size() + alreadyCount;
                LOGGER.info("PC端获取贴吧列表成功，共 {} 个贴吧，需签到 {} 个", followNum, forums.size());
                hasForums = true;
            } else if (alreadyCount > 0) {
                followNum = alreadyCount;
                LOGGER.info("PC端获取贴吧列表成功，全部 {} 个已签到", alreadyCount);
                hasForums = true;
            } else {
                LOGGER.info("PC端未获取到需要签到的贴吧");
            }

        } catch (Exception e) {
            LOGGER.error("PC端获取贴吧列表出错 -- {}", e.getMessage());
        }

        // 阶段2: PC端为空，尝试移动端API
        if (!hasForums) {
            LOGGER.info("===== 阶段2: PC端为空，尝试移动端API获取 =====");
            try {
                int pageNo = 1;
                boolean hasMore = true;

                while (hasMore) {
                    Map<String, String> data = new LinkedHashMap<>(CLIENT_PARAMS);
                    data.put("BDUSS", Cookie.getInstance().getBDUSS());
                    data.put("_client_id", "wappc_1534235498291_488");
                    data.put("from", "1008621y");
                    data.put("page_no", String.valueOf(pageNo));
                    data.put("page_size", "200");
                    data.put("timestamp", String.valueOf(System.currentTimeMillis() / 1000));
                    data.put("vcode_tag", "11");

                    String body = Encryption.encodeSign(data);
                    JSONObject jsonObject = Request.postWithRetry(LIKE_URL_MOBILE, body, 3);

                    if (jsonObject == null) {
                        LOGGER.error("移动端获取第 {} 页贴吧列表失败", pageNo);
                        break;
                    }

                    LOGGER.info("移动端获取第 {} 页贴吧列表", pageNo);

                    try {
                        JSONObject forumList = jsonObject.getJSONObject("forum_list");
                        if (forumList != null) {
                            for (String type : new String[]{"non-gconforum", "gconforum"}) {
                                JSONArray items = forumList.getJSONArray(type);
                                if (items != null && !items.isEmpty()) {
                                    for (Object item : items) {
                                        JSONObject forum = (JSONObject) item;
                                        String name = forum.getString("name");
                                        String id = forum.getString("id");
                                        if (name != null && id != null) {
                                            forums.add(new ForumInfo(name, id));
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.error("移动端解析贴吧列表出错 -- {}", e.getMessage());
                    }

                    String hasMoreStr = jsonObject.getString("has_more");
                    if (!"1".equals(hasMoreStr)) {
                        hasMore = false;
                    } else {
                        pageNo++;
                        Thread.sleep(500 + new Random().nextInt(1500));
                    }
                }

                if (!forums.isEmpty()) {
                    followNum = forums.size();
                    LOGGER.info("移动端获取贴吧列表成功，共 {} 个贴吧", followNum);
                    hasForums = true;
                } else {
                    LOGGER.info("移动端也未获取到贴吧");
                }

            } catch (Exception e) {
                LOGGER.error("移动端获取贴吧列表出错 -- {}", e.getMessage());
            }
        }

        // 阶段3: 都为空，使用后备方式（单页PC端）
        if (!hasForums) {
            LOGGER.info("===== 阶段3: 前两阶段均为空，使用后备方式 =====");
            try {
                JSONObject jsonObject = Request.get(LIKE_URL);
                LOGGER.info("使用后备方式获取贴吧列表");
                JSONArray jsonArray = jsonObject.getJSONObject("data").getJSONArray("like_forum");
                followNum = jsonArray.size();
                for (Object array : jsonArray) {
                    JSONObject obj = (JSONObject) array;
                    String tiebaName = obj.getString("forum_name");
                    if ("0".equals(obj.getString("is_sign"))) {
                        followNames.add(tiebaName.replace("+", "%2B"));
                    } else {
                        alreadyCount++;
                    }
                }
                LOGGER.info("后备方式获取贴吧成功，共 {} 个，需签到 {} 个", followNum, followNames.size());
                hasForums = true;
            } catch (Exception e2) {
                LOGGER.error("后备方式也失败 -- {}", e2.getMessage());
            }
        }

        if (!hasForums && forums.isEmpty() && followNames.isEmpty()) {
            LOGGER.error("所有方式均未能获取到贴吧列表，请检查BDUSS是否有效");
        }
    }

    /**
     * 开始签到 - 有ID用移动端签名，无ID用PC端签名
     *
     * @author srcrs
     * @Time 2020-10-31
     */
    public void runSign() {
        try {
            // 有ForumInfo（PC/移动端获取的带ID数据）→ 使用移动端签名
            if (!forums.isEmpty()) {
                LOGGER.info("使用移动端签名方式，开始签到 {} 个贴吧", forums.size());
                for (int i = 0; i < forums.size(); i++) {
                    ForumInfo forum = forums.get(i);
                    String forumName = forum.name;
                    String forumId = forum.id;

                    Map<String, String> data = new LinkedHashMap<>(CLIENT_PARAMS);
                    data.put("BDUSS", Cookie.getInstance().getBDUSS());
                    data.put("fid", forumId != null ? forumId : "");
                    data.put("kw", forumName);
                    data.put("tbs", tbs);
                    data.put("timestamp", String.valueOf(System.currentTimeMillis() / 1000));

                    String body = Encryption.encodeSign(data);
                    JSONObject result = Request.postWithRetry(SIGN_URL, body, 3);

                    String errorCode = (result != null) ? result.getString("error_code") : null;
                    if ("0".equals(errorCode)) {
                        successCount++;
                        try {
                            String rank = result.getJSONObject("user_info").getString("user_sign_rank");
                            LOGGER.info("【{}】吧({}/{}) 签到成功，第{}个签到", forumName, i + 1, forums.size(), rank);
                        } catch (Exception e) {
                            LOGGER.info("【{}】吧({}/{}) 签到成功", forumName, i + 1, forums.size());
                        }
                    } else if ("160002".equals(errorCode)) {
                        alreadyCount++;
                        LOGGER.info("【{}】吧({}/{}) 今日已签到", forumName, i + 1, forums.size());
                    } else if ("340006".equals(errorCode)) {
                        blockedCount++;
                        LOGGER.warn("【{}】吧({}/{}) 贴吧已被屏蔽", forumName, i + 1, forums.size());
                    } else {
                        errorCount++;
                        String errorMsg = (result != null) ? result.getString("error_msg") : "请求失败";
                        LOGGER.warn("【{}】吧({}/{}) 签到失败: {}", forumName, i + 1, forums.size(), errorMsg);
                    }

                    int delay = 1000 + new Random().nextInt(1500);
                    TimeUnit.MILLISECONDS.sleep(delay);

                    if ((i + 1) % 10 == 0 && i + 1 < forums.size()) {
                        int extraSleep = 5000 + new Random().nextInt(5000);
                        LOGGER.info("已签到 {}/{} 个贴吧，休息 {} 秒", i + 1, forums.size(), extraSleep / 1000.0);
                        TimeUnit.MILLISECONDS.sleep(extraSleep);
                    }
                }

            // 后备方式获取的无ID数据（followNames）→ 使用PC端MD5签名
            } else if (!followNames.isEmpty()) {
                LOGGER.info("使用PC端签名方式（后备），开始签到 {} 个贴吧", followNames.size());
                Integer flag = 5;

                while (!followNames.isEmpty() && flag > 0) {
                    LOGGER.info("-----第 {} 轮签到开始-----", 5 - flag + 1);
                    LOGGER.info("还剩 {} 贴吧需要签到", followNames.size());
                    Iterator<String> iterator = followNames.iterator();
                    while (iterator.hasNext()) {
                        String s = iterator.next();
                        String rotation = s.replace("%2B", "+");
                        String body = "kw=" + s + "&tbs=" + tbs + "&sign="
                                + Encryption.enCodeMd5("kw=" + rotation + "tbs=" + tbs + "tiebaclient!!!");
                        JSONObject post = Request.post(SIGN_URL, body);
                        int randomTime = new Random().nextInt(200) + 300;
                        TimeUnit.MILLISECONDS.sleep(randomTime);
                        if ("0".equals(post.getString("error_code"))) {
                            iterator.remove();
                            successCount++;
                            LOGGER.info(rotation + ": " + "签到成功");
                        } else {
                            LOGGER.warn(rotation + ": " + "本轮签到失败，将重试");
                        }
                    }
                    if (!followNames.isEmpty()) {
                        flag--;
                        if (flag > 0) {
                            Thread.sleep(1000 * 60 * 5);
                            getTbs();
                        }
                    }
                }
                // 剩余未签到的计入失败
                errorCount += followNames.size();
            }
        } catch (Exception e) {
            LOGGER.error("签到部分出现错误 -- " + e);
        }
    }

    /**
     * 发送运行结果到微信，通过 server 酱
     *
     * @param sckey
     * @author srcrs
     * @Time 2020-10-31
     */
    public void send(String sckey) {
        try {
            String text = "总: " + followNum + " - ";
            text += "成功: " + successCount + " 已签: " + alreadyCount
                    + " 屏蔽: " + blockedCount + " 失败: " + errorCount;
            String desp = "共 " + followNum + " 个贴吧\n\n";
            desp += "成功: " + successCount + " 已签到: " + alreadyCount
                    + " 屏蔽: " + blockedCount + " 失败: " + errorCount;
            String body = "text=" + text + "&desp=" + "TiebaSignIn运行结果\n\n" + desp;
            StringEntity entityBody = new StringEntity(body, "UTF-8");
            HttpClient client = HttpClients.createDefault();
            HttpPost httpPost = new HttpPost("https://sc.ftqq.com/" + sckey + ".send");
            httpPost.addHeader("Content-Type", "application/x-www-form-urlencoded");
            httpPost.setEntity(entityBody);
            HttpResponse resp = client.execute(httpPost);
            HttpEntity entity = resp.getEntity();
            String respContent = EntityUtils.toString(entity, "UTF-8");
            LOGGER.info("server酱发送成功 -- ");
        } catch (Exception e) {
            LOGGER.error("server酱发送失败 -- " + e);
        }
    }
}