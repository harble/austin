package com.java3y.austin.handler.handler.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.IORuntimeException;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.http.ContentType;
import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.alibaba.fastjson.JSON;
import com.google.common.base.Throwables;
import com.java3y.austin.common.domain.RecallTaskInfo;
import com.java3y.austin.common.domain.TaskInfo;
import com.java3y.austin.common.dto.model.UrlContentModel;
import com.java3y.austin.common.enums.ChannelType;
import com.java3y.austin.common.enums.EnumUtil;
import com.java3y.austin.common.enums.RespStatusEnum;
import com.java3y.austin.handler.handler.BaseHandler;
import com.java3y.austin.handler.handler.Handler;
import com.java3y.austin.support.utils.OkHttpUtils;
import io.netty.handler.timeout.ReadTimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.conn.ConnectTimeoutException;
import org.springframework.stereotype.Service;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.*;

import org.json.JSONObject;

/**
 * URL回调 消息处理器
 *
 * @author
 */
@Slf4j
@Service
public class UrlHander extends BaseHandler implements Handler {

    //@Autowired
    //private AccountUtils accountUtils;

    public static void main(String[] args) throws Exception {
        // 示例JSON字符串
        String jsonStr = "{\"name\": \"John\", \"age\": 30, \"city\": \"New York\"}";
        // 将JSON字符串解析为JSON对象
        JSONObject jsonObject = new JSONObject(jsonStr);

        String queryString = OkHttpUtils.jsonToQueryString( jsonObject);

        jsonStr = "{\n" +
                "\"Headers\": {\n" +
                "  \"Content-type\": \"application/json;\"\n" +
                "},\n" +
                "\"Options\": {\n" +
                "  \"authKey\": \"1\",\n" +
                "  \"method\": \"POST\",\n" +
                "  \"retry\": \"0\"\n" +
                "},\n" +
                "\"Params\": {\n" +
                "  \"code\": \"2\",\n" +
                "  \"state\": \"3\",\n" +
                "  \"type\": \"4\",\n" +
                "  \"message\": \"5\"\n" +
                "}\n" +
                "}";
        UrlContentModel ucm = JSON.parseObject(jsonStr, UrlContentModel.class);

        queryString =  OkHttpUtils.mapToQueryString( ucm.getParams() );

        assembleHeaderAuth(ucm.getHeaders(), "http://WWW.sina.com.cn", "ABC123");

        jsonStr = "";
    }

    public UrlHander() {
        channelCode = ChannelType.URL.getCode();
    }

    // 重试发送，最多4次。 4次时，依次间隔 6秒、60秒、600秒、6000秒...
    public void retry(int num, TaskInfo taskInfo) {
        long schedule = 6 * 1000; // 6秒后重试
        taskInfo.setRetryCount(1);
        Timer timer = new Timer();
        TimerTask task = new TimerTask() {
            @Override
            public void run() {
                int mask = 10000, runs = taskInfo.getRetryCount() % mask, count = taskInfo.getRetryCount() / mask;
                if ( runs >= Math.pow(10, count) ) { // 6秒、60秒、600秒... 重试
                    count ++;
                    int ret = 1;
                    try { ret = execute(taskInfo); } catch (Exception e) {}

                    log.info("No.{} retry callback url:{}, result: {}", count, JSON.toJSONString(taskInfo.getReceiver()), ret);
                    if (ret == 1) {
                        // 使用warn信息来作为发送记录保存
                        String channelDesc = EnumUtil.getDescriptionByCode(taskInfo.getSendChannel(), ChannelType.class);
                        log.warn("{}, {}, {}, {}", taskInfo.getMessageTemplateId(), channelDesc, taskInfo.getReceiver(), JSON.toJSONString(taskInfo.getContentModel()));
                    }
                    if ( ret != -1 || count >= num || count > 3 ) cancel();
                }

                runs ++;
                taskInfo.setRetryCount( count*mask + runs);
            }
        };

        timer.scheduleAtFixedRate(task, 1000, schedule); // schedule(task, delay);
    }

    public int execute(TaskInfo taskInfo) throws Exception {
        Set<String> urls = taskInfo.getReceiver();
        if ( urls==null || urls.size()==0 ) throw new Exception(RespStatusEnum.RESOURCE_NOT_FOUND.name());
        String url = urls.iterator().next();

        UrlContentModel ucm = (UrlContentModel)taskInfo.getContentModel();
        String method = getMapValue(ucm.getOptions(), "method", "GET" );
        String authKey = getMapValue(ucm.getOptions(), "authKey", null );

        Map<String, String> headers = ucm.getHeaders();
        assembleHeaderAuth(headers, url, authKey);

        HttpRequest httpRequest = null;
        if ( method.indexOf("GET") != -1 ) {
            String qs =  OkHttpUtils.mapToQueryString( ucm.getParams() );
            if (qs!=null && !qs.isEmpty()) url = url + (url.indexOf("?")!=-1 ? "&" : "?") + qs;
            httpRequest = HttpRequest.get(url);
        }
        else {
            httpRequest = HttpRequest.post(url);
            httpRequest.body(JSON.toJSONString(ucm.getParams()));
            headers.put(Header.CONTENT_TYPE.getValue(), ContentType.JSON.getValue());
        }

        for ( String key : headers.keySet() ) {
            httpRequest.header(key, headers.get(key));
        }

        Integer retryCount = taskInfo.getRetryCount();
        if ( retryCount==null || retryCount<=0 ) retryCount = 1;
        else retryCount ++;

        try {
            HttpResponse response = httpRequest.timeout(5000 * retryCount).execute();
            // ReturnT returnT = JSON.parseObject(response.body(), ReturnT.class);
            log.info("UrlHander#execute url:{}  response:{}", url, response.body().substring(0, response.body().length()>1024? 1024 : response.body().length()));
            if (response.isOk()) // && ReturnT.SUCCESS_CODE == returnT.getCode())
                return 1;
        }
        catch(Exception e) {
            if (e instanceof IORuntimeException || e instanceof InterruptedIOException || e instanceof UnknownHostException ||
                    e instanceof ConnectTimeoutException || e instanceof SocketTimeoutException || e instanceof ReadTimeoutException) {
                log.error("UrlHander#execute url:{}  exception:{}", url,  e.getMessage());
                return -1; // 需要重发
            }
            else throw new Exception(e);
        }

        //System.out.println("定时任务执行时间：" + System.currentTimeMillis());
        return 0;
    }

    @Override
    public boolean handler(TaskInfo taskInfo) {
        try {
            /* // taskInfo example
              {
              "bizId": "ck5W6qaSMDw_1j9QJb6du",
              "businessId": 2000002220231007,
              "contentModel": {
                "Headers": {
                  "Content-type": "application/json;"
                },
                "Options": {
                  "authKey": "1",
                  "method": "POST",
                  "retry": "0"
                },
                "Params": {
                  "code": "2",
                  "state": "3",
                  "type": "4",
                  "message": "5"
                }
              },
              "idType": 100,
              "messageId": "ck5W6qaSMDw_1j9QJb6du",
              "messageTemplateId": 22,
              "msgType": 10,
              "receiver": [
                "www.sian.com.cn"
              ],
              "retryCount": 1,
              "sendAccount": -1,
              "sendChannel": 130,
              "shieldType": 10,
              "templateType": 20
            }
            */

            UrlContentModel ucm = (UrlContentModel)taskInfo.getContentModel();
            String retryNum = getMapValue(ucm.getOptions(), "retry", "2" );

            int ret = execute(taskInfo);
            if ( ret == -1 ) { // 需要尝试重发
                retry( Integer.parseInt(retryNum), taskInfo);
            }
            log.info("UrlHander#handler result:{}, call:{}, Content:{}", ret, taskInfo.getReceiver(), taskInfo.getContentModel());

            return ret == 1;
        } catch (Exception e) {
            log.error("UrlHander#handler fail! e:{}, params:{}", Throwables.getStackTraceAsString(e), JSON.toJSONString(taskInfo));
        }
        return false;
    }

    /**
     * 组装发送Header参数
     */
    private static void assembleHeaderAuth(Map<String, String> headers, String oriUrl, String authKey) {
        if ( headers == null ) headers = new HashMap<>();
        if (authKey == null || authKey.isEmpty())  return;

        // UNIX时间戳，整型正数，固定长度13，1970年1月1日以来的毫秒数，表示回调请求发起时间。
        long time = DateUtil.date().getTime();
        // 签名字符串，为32位MD5值
        // MD5Content = URL|X-SPC-TIMESTAMP|AuthKey
        String sign = SecureUtil.md5(oriUrl + "|" + time + "|" + authKey);

        headers.put("X-SPC-TIMESTAMP", time + "");
        headers.put("X-SPC-SIGNATURE", sign);
    }

    private String getMapValue(Map<String, String> options, String key, String defVal) {
        return ( options==null || options.isEmpty() || options.get(key)==null) ? defVal : options.get(key);
    }

    @Override
    public void recall(RecallTaskInfo recallTaskInfo) {

    }
}

