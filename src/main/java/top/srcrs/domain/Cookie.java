package top.srcrs.domain;

/**
 * 存入用户所填写的BDUSS
 * @author srcrs
 * @Time 2020-10-31
 */
public class Cookie {
    private static final Cookie cookie = new Cookie();
    private String BDUSS;
    private Cookie(){};

    public static Cookie getInstance() {
        return cookie;
    }

    public String getBDUSS() {
        return BDUSS;
    }

    public void setBDUSS(String BDUSS) {
        this.BDUSS = BDUSS;
    }
    public String getCookie() {
        return "BDUSS="+BDUSS;
    }
}
