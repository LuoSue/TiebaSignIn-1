package top.srcrs;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
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
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
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
     * 获取用户的tbs
     */
    String TBS_URL = "http://tieba.baidu.com/dc/common/tbs";
    /**
     * 贴吧签到接口
     */
    String SIGN_URL = "http://c.tieba.baidu.com/c/c/forum/sign";

    /**
     * 存储用户所关注的贴吧
     */
    private List<String> follow = new ArrayList<>();
    /**
     * 签到成功的贴吧列表
     */
    private static List<String> success = new ArrayList<>();

    /**
     * 签到失败的贴吧列表
     */
    private static HashSet<String> failed = new HashSet<String>();

    /**
     * 失效的贴吧列表
     */
    private static List<String> invalid = new ArrayList<>();

    /**
     * 用户的tbs
     */
    private String tbs = "";
    /**
     * 用户所关注的贴吧数量
     */
    private static Integer followNum = 0;

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
        LOGGER.info("共 {} 个贴吧 - 成功: {} - 失败: {} - {} ", followNum, success.size(), followNum - success.size(), failed);
        LOGGER.info("失效 {} 个贴吧: {} ", invalid.size(), invalid);
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
     * 获取用户所关注的贴吧列表 - 支持分页获取，突破200个限制
     *
     * @author srcrs
     * @Time 2020-10-31
     */
    public void getFollow() {
        try {
            int page = 1;
            int perPage = 50;
            boolean hasMore = true;
            
            while (hasMore) {
                String pageUrl = LIKE_URL + "?pn=" + page + "&rn=" + perPage;
                JSONObject jsonObject = Request.get(pageUrl);
                
                LOGGER.info("获取第 {} 页贴吧列表", page);
                
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
                
                followNum += jsonArray.size();
                
                for (Object array : jsonArray) {
                    String tiebaName = null;
                    try {
                        tiebaName = ((JSONObject) array).getString("forum_name");
                    } catch (Exception e) {
                        tiebaName = ((JSONObject) array).getString("name");
                    }
                    
                    if (tiebaName == null) continue;
                    
                    String isSign = "0";
                    try {
                        isSign = ((JSONObject) array).getString("is_sign");
                    } catch (Exception e) {}
                    
                    if ("0".equals(isSign)) {
                        follow.add(tiebaName.replace("+", "%2B"));
                        if (Request.isTiebaNotExist(tiebaName)) {
                            follow.remove(tiebaName);
                            invalid.add(tiebaName);
                            failed.add(tiebaName);
                        }
                    } else {
                        success.add(tiebaName);
                    }
                }
                
                if (jsonArray.size() < perPage) {
                    hasMore = false;
                } else {
                    page++;
                    Thread.sleep(500);
                }
            }
            
            LOGGER.info("获取贴吧列表成功，共 {} 个贴吧", follow.size() + success.size());
            
        } catch (Exception e) {
            LOGGER.error("获取贴吧列表部分出现错误 -- " + e);
            try {
                JSONObject jsonObject = Request.get(LIKE_URL);
                LOGGER.info("使用后备方式获取贴吧列表");
                JSONArray jsonArray = jsonObject.getJSONObject("data").getJSONArray("like_forum");
                followNum = jsonArray.size();
                for (Object array : jsonArray) {
                    String tiebaName = ((JSONObject) array).getString("forum_name");
                    if ("0".equals(((JSONObject) array).getString("is_sign"))) {
                        follow.add(tiebaName.replace("+", "%2B"));
                    } else {
                        success.add(tiebaName);
                    }
                }
            } catch (Exception e2) {
                LOGGER.error("后备方式也失败 -- " + e2);
            }
        }
    }

    /**
     * 开始进行签到，每一轮性将所有未签到的贴吧进行签到，一共进行5轮，如果还未签到完就立即结束
     *
     * @author srcrs
     * @Time 2020-10-31
     */
    public void runSign() {
        Integer flag = 5;
        try {
            while (success.size() < followNum && flag > 0) {
                LOGGER.info("-----第 {} 轮签到开始-----", 5 - flag + 1);
                LOGGER.info("还剩 {} 贴吧需要签到", followNum - success.size());
                Iterator<String> iterator = follow.iterator();
                while (iterator.hasNext()) {
                    String s = iterator.next();
                    String rotation = s.replace("%2B", "+");
                    String body = "kw=" + s + "&tbs=" + tbs + "&sign=" + Encryption.enCodeMd5("kw=" + rotation + "tbs=" + tbs + "tiebaclient!!!");
                    JSONObject post = new JSONObject();
                    post = Request.post(SIGN_URL, body);
                    int randomTime = new Random().nextInt(200) + 300;
                    LOGGER.info("等待 {} 毫秒", randomTime);
                    TimeUnit.MILLISECONDS.sleep(randomTime);
                    if ("0".equals(post.getString("error_code"))) {
                        iterator.remove();
                        success.add(rotation);
                        failed.remove(rotation);
                        LOGGER.info(rotation + ": " + "签到成功");
                    } else {
                        failed.add(rotation);
                        LOGGER.warn(rotation + ": " + "签到失败");
                    }
                }
                if (success.size() != followNum - invalid.size()) {
                    Thread.sleep(1000 * 60 * 5);
                    getTbs();
                }
                flag--;
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
            text += "成功: " + success.size() + " 失败: " + (followNum - success.size());
            String desp = "共 " + followNum + " 贴吧\n\n";
            desp += "成功: " + success.size() + " 失败: " + (followNum - success.size());
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
