package top.srcrs.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * 对字符串进行加密
 *
 * @author srcrs
 * @Time 2020-10-31
 */
public class Encryption {
    /**
     * 获取日志记录器对象
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(Encryption.class);

    /**
     * 贴吧客户端签名密钥
     */
    private static final String SIGN_KEY = "tiebaclient!!!";

    /**
     * 对字符串进行 MD5加密，返回大写十六进制字符串，保留前导零
     *
     * @param str 传入一个字符串
     * @return String 加密后的字符串
     * @author srcrs
     * @Time 2020-10-31
     */
    public static String enCodeMd5(String str) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(str.getBytes("UTF-8"));
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString().toUpperCase();
        } catch (Exception e) {
            LOGGER.error("字符串进行MD5加密错误 -- " + e);
            return "";
        }
    }

    /**
     * 对贴吧移动端请求参数进行签名
     * 按 key 字母序排序，拼接 key=value，加 tiebaclient!!! 后做 MD5
     *
     * @param data 请求参数
     * @return 签名后的参数字符串（URL form 编码格式，含 sign 字段）
     */
    public static String encodeSign(Map<String, String> data) {
        TreeMap<String, String> sorted = new TreeMap<>(data);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        String sign = enCodeMd5(sb.toString() + SIGN_KEY);
        sorted.put("sign", sign);

        // 构建 URL form 编码的参数字符串
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (result.length() > 0) {
                result.append("&");
            }
            result.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return result.toString();
    }
}