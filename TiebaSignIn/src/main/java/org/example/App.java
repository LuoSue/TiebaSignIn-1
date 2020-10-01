package org.example;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Scanner;

/**
 * Hello world!
 *
 */
public class App
{
    String LIKE_URL = "https://tieba.baidu.com/mo/q/newmoindex";
    String TBS_URL = "http://tieba.baidu.com/dc/common/tbs";
    String SIGN_URL = "http://c.tieba.baidu.com/c/c/forum/sign";

    static int index=0;

    static String BDUSS = "";

    String tbs = "";

    public static void main( String[] args ) throws Exception {
        BDUSS = args[0];
        App app = new App();
        app.getTbs();
        app.getFollow();
    }

    /**
     * 进行登录，获得tbs，签到的时候需要用到这个参数
     * @throws Exception
     */
    public void getTbs() throws Exception {
        System.out.println("开始进行登录...");
        URL url = new URL(TBS_URL);
        //得到connection对象。
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        //设置请求方式
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent","Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/39.0.2171.71 Safari/537.36");
        connection.setRequestProperty("Cookie", "BDUSS="+BDUSS);//设置请求头
        //连接
        connection.connect();
        //得到响应码
        int responseCode = connection.getResponseCode();
        if(responseCode == HttpURLConnection.HTTP_OK){
            //得到响应流
            InputStream inputStream = connection.getInputStream();
            //将响应流转换成字符串
            String result = new Scanner(inputStream).useDelimiter("\\Z").next();//将流转换为字符串。
            JSONObject jsonObject = JSONObject.parseObject(result);
            if("1".equals(jsonObject.getString("is_login"))){
                System.out.println("登录成功-------->" + result);
                tbs = jsonObject.getString("tbs");
            }
            else {
                System.out.println("登录失败");
            }
        }
    }

    /**
     * 获取关注的所有贴吧，限制200个
     * @throws Exception
     */
    public void getFollow() throws Exception{
        System.out.println("开始获取贴吧列表...");
        URL url = new URL(LIKE_URL);
        //得到connection对象。
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        //设置请求方式
        connection.setRequestMethod("GET");
        connection.setRequestProperty("connection","keep-alive");
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/39.0.2171.71 Safari/537.36");
        connection.setRequestProperty("Cookie", "BDUSS="+BDUSS);//设置请求头
        //连接
        connection.connect();
        //得到响应码
        int responseCode = connection.getResponseCode();
        if(responseCode == HttpURLConnection.HTTP_OK){
            //得到响应流
            InputStream inputStream = connection.getInputStream();
            //将响应流转换成字符串
            String result = new Scanner(inputStream).useDelimiter("\\Z").next();//将流转换为字符串。
            JSONArray jsonArray = JSON.parseObject(result).getJSONObject("data").getJSONArray("like_forum");
            System.out.println("贴吧列表获取成功!!!");
            System.out.println("开始签到操作...");
            for (Object array : jsonArray) {
                JSONObject jb = (JSONObject)array;
                runSign(jb.getString("forum_name"));
            }
        }
        else{
            System.out.println("获取贴吧列表失败!!!");
        }
    }

    /**
     * 开始进行签到，每解析出一个贴吧，立刻进行签到
     * @param name
     * @throws Exception
     */
    public void runSign(String name) throws Exception{
        URL url = new URL(SIGN_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setDoInput(true);
        connection.setUseCaches(false);
        connection.setRequestProperty("connection","keep-alive");
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/39.0.2171.71 Safari/537.36");
        connection.setRequestProperty("Cookie", "BDUSS="+BDUSS);//设置请求头
        connection.setRequestProperty("Content-Type", "application/json;charset=utf-8");//设置参数类型是json格式
        connection.connect();

        String body = "kw="+name+"&tbs="+tbs+"&sign="+enCodeMd5("kw="+name+"tbs="+tbs+"tiebaclient!!!");

        /**
         * 说起来都是泪，为什么这种方式不行，还非得弄成get添加参数的形式，唉。
         */
//        String body = "{\n" +
//                "  \"kw\": \""+name+"\"\n" +
//                "  \"tbs\": \""+tbs+"\"\n" +
//                "  \"sign\": \""+enCodeMd5("kw="+name+"tbs="+tbs+"tiebaclient!!!")+"\"\n" +
//                "}";
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(connection.getOutputStream(), "UTF-8"));
        writer.write(body);
        writer.close();

        int responseCode = connection.getResponseCode();
        if(responseCode == HttpURLConnection.HTTP_OK){
            //得到响应流
            InputStream inputStream = connection.getInputStream();
            //将响应流转换成字符串
            String result = new Scanner(inputStream).useDelimiter("\\Z").next();//将流转换为字符串。
            JSONObject jsonObject = JSON.parseObject(result);
            if("0".equals(jsonObject.getString("error_code"))){
                System.out.println(++index + "-------->" + name + "签到成功");
            } else {
                System.out.println("error" + "-------->" + name + "签到失败");
            }
        }
    }

    /**
     * 进行md5加密
     * @param str
     * @return
     * @throws Exception
     */
    public static String enCodeMd5(String str) throws Exception {
        // 生成一个MD5加密计算摘要
        MessageDigest md = MessageDigest.getInstance("MD5");
        // 计算md5函数
        md.update(str.getBytes("UTF-8"));
        // digest()最后确定返回md5 hash值，返回值为8位字符串。因为md5 hash值是16位的hex值，实际上就是8位的字符
        // BigInteger函数则将8位的字符串转换成16位hex值，用字符串来表示；得到字符串形式的hash值
        //一个byte是八位二进制，也就是2位十六进制字符（2的8次方等于16的2次方）

        return new BigInteger(1, md.digest()).toString(16);
    }
}
