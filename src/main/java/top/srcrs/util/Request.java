package top.srcrs.util;

import com.alibaba.fastjson.JSONObject;
import org.apache.http.*;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.srcrs.domain.Cookie;

/**
 * 封装的网络请求请求工具类
 * @author srcrs
 * @Time 2020-10-31
 */
public class Request {
    /** 获取日志记录器对象 */
    private static final Logger LOGGER = LoggerFactory.getLogger(Request.class);
    /** 获取Cookie对象 */
    private static Cookie cookie = Cookie.getInstance();
    private Request(){};

    /**
     * 发送get请求
     * @param url 请求的地址，包括参数
     * @return JSONObject
     * @author srcrs
     * @Time 2020-10-31
     */
    public static JSONObject get(String url){
        RequestConfig defaultConfig = RequestConfig.custom().setCookieSpec(CookieSpecs.STANDARD).build();
        HttpClient client = HttpClients.custom().setDefaultRequestConfig(defaultConfig).build();

        HttpGet httpGet = new HttpGet(url);
        httpGet.addHeader("connection","keep-alive");
        httpGet.addHeader("Content-Type","application/x-www-form-urlencoded");
        httpGet.addHeader("charset","UTF-8");
        httpGet.addHeader("User-Agent","Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/39.0.2171.71 Safari/537.36");
        httpGet.addHeader("Cookie",cookie.getCookie());
        HttpResponse resp = null;
        String respContent = null;
        try{
            resp = client.execute(httpGet);
            HttpEntity entity=null;
            if(resp.getStatusLine().getStatusCode()<400){
                entity = resp.getEntity();
            } else{
                entity = resp.getEntity();
            }
            respContent = EntityUtils.toString(entity, "UTF-8");
        } catch (Exception e){
            LOGGER.info("get请求错误 -- "+e);
        } finally {
            return JSONObject.parseObject(respContent);
        }
    }

    /**
     * 发送post请求
     * @param url 请求的地址
     * @param body 携带的参数
     * @return JSONObject
     * @author srcrs
     * @Time 2020-10-31
     */
    public static JSONObject post(String url , String body){
        StringEntity entityBody = new StringEntity(body,"UTF-8");
        RequestConfig defaultConfig = RequestConfig.custom().setCookieSpec(CookieSpecs.STANDARD).build();
        HttpClient client = HttpClients.custom().setDefaultRequestConfig(defaultConfig).build();
        HttpPost httpPost = new HttpPost(url);
        httpPost.addHeader("connection","keep-alive");
        httpPost.addHeader("Host","tieba.baidu.com");
        httpPost.addHeader("Content-Type","application/x-www-form-urlencoded");
        httpPost.addHeader("charset","UTF-8");
        httpPost.addHeader("User-Agent","Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/39.0.2171.71 Safari/537.36");
        httpPost.addHeader("Cookie",cookie.getCookie());
        httpPost.setEntity(entityBody);
        HttpResponse resp = null;
        String respContent = null;
        try{
            resp = client.execute(httpPost);
            HttpEntity entity=null;
            if(resp.getStatusLine().getStatusCode()<400){
                entity = resp.getEntity();
            } else{
                entity = resp.getEntity();
            }
            respContent = EntityUtils.toString(entity, "UTF-8");
        } catch (Exception e){
            LOGGER.info("post请求错误 -- "+e);
        }
        finally {
            return JSONObject.parseObject(respContent);
        }
    }
}

