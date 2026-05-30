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
     * 获取用户所有关注贴吧 - 移动端API接口
     */
    private static final String LIKE_URL = "http://c.tieba.baidu.com/c/f/forum/like";
    /**
     * 获取用户的tbs
     */
    private static final String TBS_URL = "http://tieba.baidu.com/dc/common/tbs";
    /**
     * 贴吧签到接口
     */
    private static final String SIGN_URL = "http://c.tieba.baidu.com/c/c/forum/sign";

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
     * 存储用户所关注的贴吧
     */
    private List<ForumInfo> forums = new ArrayList<>();

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
            if (jsonObject != null && "1".equals(jsonObject.getString("is_login"))) {
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
     * 获取用户所关注的贴吧列表 - 使用移动端API，支持分页获取
     *
     * @author srcrs
     * @Time 2020-10-31
     */
    public void getFollow() {
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
                JSONObject jsonObject = Request.postWithRetry(LIKE_URL, body, 3);

                if (jsonObject == null) {
                    LOGGER.error("获取第 {} 页贴吧列表失败", pageNo);
                    break;
                }

                LOGGER.info("获取第 {} 页贴吧列表", pageNo);

                // 解析 forum_list 中的 non-gconforum 和 gconforum
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
                    LOGGER.error("解析贴吧列表出错 -- " + e);
                }

                // 检查是否有更多页
                String hasMoreStr = jsonObject.getString("has_more");
                if (!"1".equals(hasMoreStr)) {
                    hasMore = false;
                } else {
                    pageNo++;
                    Thread.sleep(500 + new Random().nextInt(1500));
                }
            }

            followNum = forums.size();
            LOGGER.info("获取贴吧列表成功，共 {} 个贴吧", followNum);

        } catch (Exception e) {
            LOGGER.error("获取贴吧列表部分出现错误 -- " + e);
        }
    }

    /**
     * 开始进行签到，对每个贴吧逐一签到，每次请求最多重试3次（指数退避）
     *
     * @author srcrs
     * @Time 2020-10-31
     */
    public void runSign() {
        try {
            LOGGER.info("开始签到 {} 个贴吧", forums.size());

            for (int i = 0; i < forums.size(); i++) {
                ForumInfo forum = forums.get(i);
                String forumName = forum.name;
                String forumId = forum.id;

                // 构建签到请求参数
                Map<String, String> data = new LinkedHashMap<>(CLIENT_PARAMS);
                data.put("BDUSS", Cookie.getInstance().getBDUSS());
                data.put("fid", forumId);
                data.put("kw", forumName);
                data.put("tbs", tbs);
                data.put("timestamp", String.valueOf(System.currentTimeMillis() / 1000));

                String body = Encryption.encodeSign(data);
                JSONObject result = Request.postWithRetry(SIGN_URL, body, 3);

                // 处理结果
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

                // 请求间隔 1.0~2.5 秒
                int delay = 1000 + new Random().nextInt(1500);
                TimeUnit.MILLISECONDS.sleep(delay);

                // 每签到 10 个贴吧额外休息 5~10 秒
                if ((i + 1) % 10 == 0 && i + 1 < forums.size()) {
                    int extraSleep = 5000 + new Random().nextInt(5000);
                    LOGGER.info("已签到 {}/{} 个贴吧，休息 {} 秒", i + 1, forums.size(), extraSleep / 1000.0);
                    TimeUnit.MILLISECONDS.sleep(extraSleep);
                }
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
