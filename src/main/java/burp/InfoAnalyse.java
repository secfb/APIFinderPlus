package burp;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import model.FingerPrintRule;
import model.HttpMsgInfo;
import utils.ElementUtils;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static burp.BurpExtender.*;
import static utils.BurpPrintUtils.*;
import static utils.InfoAnalyseUtils.*;

public class InfoAnalyse {

    public static final String type = "type";
    public static final String describe = "describe";
    public static final String accuracy = "accuracy";
    public static final String important = "important";
    public static final String value = "value";

    private static final int MAX_HANDLE_SIZE = 50000; //如果数组超过 50000 个字节，则截断

    public static JSONObject analysisMsgInfo(HttpMsgInfo msgInfo) {
        //TODO:过滤功能不正常 需要调试修复

        //提取URL和PATH信息
        Set<String> uriSet = findUriInfo(msgInfo);
        stdout_println(LOG_DEBUG, String.format("[*] 采集URL|PATH数量:%s", uriSet.size()));

        //拆分提取的URL和PATH为两个 List 用于进一步处理操作
        Map<String, List> map = SeparateUrlOrPath(uriSet);

        //采集URL处理
        List<String> urlList = map.get(URL_KEY);
        stdout_println(LOG_DEBUG, String.format("[*] 初步采集URL数量:%s -> %s", urlList.size(), urlList));

        //实现响应url过滤
        urlList = filterUrls(msgInfo, urlList);
        stdout_println(LOG_DEBUG, String.format("[+] 有效URL数量: %s -> %s", msgInfo.getReqUrl(), urlList.size()));
        for (String s : urlList)
            stdout_println(LOG_DEBUG, String.format("[*] INFO URL: %s", s));

        //采path处理
        List<String> pathList = map.get(PATH_KEY);
        stdout_println(LOG_DEBUG, String.format("[*] 初步采集PATH数量:%s -> %s", urlList.size(), urlList));

        //实现响应Path过滤
        pathList = filterPaths(msgInfo, pathList);
        stdout_println(LOG_DEBUG, String.format("[+] 有效PATH数量: %s -> %s", msgInfo.getReqUrl(), pathList.size()));
        for (String s : pathList)
            stdout_println(LOG_DEBUG, String.format("[*] INFO PATH: %s", s));

        //实现响应敏感信息提取
        JSONArray findInfoArray = findSensitiveInfoByConfig(msgInfo);
        //todo: 遍历敏感信息Array,删除 实际上为空的敏感信息
        stdout_println(LOG_DEBUG, String.format("[+] 敏感信息数量:%s -> %s", findInfoArray.size(), findInfoArray.toJSONString()));

        ///////////////////////////返回最终结果///////////////////////////
        JSONObject analyseInfo = new JSONObject();
        analyseInfo.put(URL_KEY, urlList);
        analyseInfo.put(PATH_KEY, pathList);
        analyseInfo.put(INFO_KEY, findInfoArray);
        stdout_println(LOG_DEBUG, String.format("[+] 最终解析结果:%s", analyseInfo.toJSONString()));
        return analyseInfo;
    }

    /**
     * 过滤提取的路径
     * @param msgInfo
     * @param pathList
     * @return
     */
    private static List<String> filterPaths(HttpMsgInfo msgInfo, List<String> pathList) {
        //过滤重复内容
        pathList = removeDuplicates(pathList);
        stdout_println(LOG_DEBUG, String.format("[*] 过滤重复PATH内容:%s -> %s", pathList.size(), pathList));

        //过滤自身包含的Path (包含说明相同)
        pathList = filterUriBySelfContain(msgInfo.getReqPath(), pathList);
        stdout_println(LOG_DEBUG, String.format("[*] 过滤自身包含的PATH:%s -> %s", pathList.size(), pathList));

        //过滤包含禁止关键字的PATH //TODO: 使用该选项会影响大部分的提取情况,需要优化 和 支持关闭 使用提取选项补充
        pathList = filterPathByContainUselessKey(pathList, CONF_BLACK_PATH_KEYS);
        stdout_println(LOG_DEBUG, String.format("[*] 过滤包含禁止关键字的PATH:%s -> %s", pathList.size(), pathList));

        //过滤包含中文的PATH
        pathList = filterPathByContainChinese(pathList);
        stdout_println(LOG_DEBUG, String.format("[*] 过滤中文PATH内容:%s -> %s", pathList.size(), pathList));

        //过滤等于禁止PATH的PATH
        pathList = filterPathByEqualUselessPath(pathList, CONF_BLACK_PATH_EQUALS);
        stdout_println(LOG_DEBUG, String.format("[*] 过滤等于禁止PATH的PATH:%s -> %s", pathList.size(), pathList));
        return pathList;
    }


    /**
     * 过滤提取的URL
     * @param msgInfo
     * @param urlList
     * @return
     */
    private static List<String> filterUrls(HttpMsgInfo msgInfo, List<String> urlList) {
        //过滤重复内容
        urlList = removeDuplicates(urlList);
        stdout_println(LOG_DEBUG, String.format("[*] 过滤重复URL内容:%s -> %s", urlList.size(), urlList));

        //仅保留主域名相关URL //功能测试通过 //TODO: 因为部分关联资产时别的IP的，所以需要支持关闭功能,使用 CONF_BLACK_URL_HOSTS 补充
        urlList = filterUrlByMainHost(msgInfo.getReqRootDomain(), urlList);
        stdout_println(LOG_DEBUG, String.format("[*] 过滤非主域名URL:%s -> %s", urlList.size(), urlList));

        //过滤自身包含的URL (包含说明相同) //功能测试通过
        urlList = filterUriBySelfContain(msgInfo.getReqUrl(), urlList);
        stdout_println(LOG_DEBUG, String.format("[*] 过滤自身包含的URL:%s -> %s", urlList.size(), urlList));

        //过滤黑名单host
        urlList = filterBlackHosts(urlList, CONF_BLACK_URL_HOSTS);
        stdout_println(LOG_DEBUG, String.format("[*] 过滤黑名单主机:%s -> %s", urlList.size(), urlList));

        //过滤黑名单Path
        urlList = filterBlackPaths(urlList, CONF_BLACK_URL_PATH);
        stdout_println(LOG_DEBUG, String.format("[*] 过滤黑名单路径:%s -> %s", urlList.size(), urlList));

        //过滤黑名单suffix
        urlList = filterBlackSuffixes(urlList, CONF_BLACK_URL_EXT);
        stdout_println(LOG_DEBUG, String.format("[*] 过滤黑名单后缀:%s -> %s", urlList.size(), urlList));
        return urlList;
    }


    /**
     * 基于规则和结果生成格式化的信息
     * @param rule
     * @param group
     * @return
     */
    private static JSONObject generateInfoJson(FingerPrintRule rule, String group) {
        JSONObject findInfo = new JSONObject();
        findInfo.put(type, rule.getType()); // "type": "敏感内容",
        findInfo.put(describe, rule.getDescribe()); //"describe": "身份证",
        findInfo.put(accuracy, rule.getAccuracy()); //"accuracy": "high"
        findInfo.put(important, rule.getIsImportant()); //"isImportant": true,
        findInfo.put(value, group);
        return findInfo;
    }


    /**
     * 根据规则提取敏感信息
     * @param msgInfo
     * @return
     */
    public static JSONArray findSensitiveInfoByConfig(HttpMsgInfo msgInfo) {
        // 使用HashSet进行去重，基于equals和hashCode方法判断对象是否相同
        Set<JSONObject> findInfosSet = new HashSet<>();

        //遍历规则进行提取
        for (FingerPrintRule rule : BurpExtender.fingerprintRules){
            //忽略关闭的选项 // 过滤掉配置选项
            if (!rule.getIsOpen() || rule.getType().contains("CONF_")){
                continue;
            }

            // 定位查找范围
            String willFindText;
            if ("body".equalsIgnoreCase(rule.getLocation())) {
                //转换响应体,后续可能需要解决编码问题
                willFindText = new String(
                        HttpMsgInfo.getBodyBytes(msgInfo.getRespBytes(), msgInfo.getRespBodyOffset()),
                        StandardCharsets.UTF_8);
                //willFindText = new String(msgInfo.getRespBytes(), StandardCharsets.UTF_8);

                //截取最大响应体长度
                willFindText = SubString(willFindText, MAX_HANDLE_SIZE);
            } else if ("urlPath".equalsIgnoreCase(rule.getLocation())) {
                willFindText = msgInfo.getReqPath();
            } else {
                stderr_println("[!] 未知指纹位置：" + rule.getLocation());
                continue;
            }

            //多个关键字匹配
            if (rule.getMatch().equals("keyword"))
                if(ElementUtils.isContainAllKey(willFindText, rule.getKeyword(), false)){
                    //匹配关键字模式成功,应该标记敏感信息
                    JSONObject findInfo = generateInfoJson(rule, String.valueOf(rule.getKeyword()));
                    stdout_println(LOG_DEBUG, String.format("[+] 关键字匹配敏感信息:%s", findInfo.toJSONString()));
                    findInfosSet.add(findInfo);
                }

            //多个正则匹配
            if (rule.getMatch().equals("regular")){
                for (String patter : rule.getKeyword()){
                    Set<String> groups = regularMatchInfo(willFindText, patter);
                    if (groups.size() > 0){
                        JSONObject findInfo = generateInfoJson(rule, String.valueOf(new ArrayList<>(groups)));
                        stdout_println(LOG_DEBUG, String.format("[+] 正则匹配敏感信息:%s", findInfo.toJSONString()));
                        findInfosSet.add(findInfo);
                    }
                }
            }
        }

        return new JSONArray(findInfosSet);
    }


    /**
     * 提取响应体中的URL和PATH
     * @param msgInfo
     * @return
     */
    public static Set<String> findUriInfo(HttpMsgInfo msgInfo) {
        //存储所有提取的URL/URI
        Set<String> uriSet = new HashSet<>();

        //转换响应体,后续可能需要解决编码问题
        String respBody = new String(
                HttpMsgInfo.getBodyBytes(msgInfo.getRespBytes(), msgInfo.getRespBodyOffset()),
                StandardCharsets.UTF_8);

        //截取最大响应体长度
        respBody = SubString(respBody, MAX_HANDLE_SIZE);

        // 针对html页面提取 直接的URL 已完成
        Set<String> extractUrlsFromHtml = extractDirectUrls(msgInfo.getReqUrl(), respBody);

        stdout_println(LOG_DEBUG, String.format("[*] 初步提取URL: %s -> %s", msgInfo.getReqUrl(), extractUrlsFromHtml.size()));
        uriSet.addAll(extractUrlsFromHtml);

        // 针对JS页面提取
        if (ElementUtils.isEqualsOneKey(msgInfo.getReqPathExt(), CONF_EXTRACT_SUFFIX, true)
                || msgInfo.getInferredMimeType().contains("script")) {
            Set<String> extractUriFromJs = extractUriFromJs(respBody);
            stdout_println(LOG_DEBUG, String.format("[*] 初步提取URI: %s -> %s", msgInfo.getReqUrl(), extractUriFromJs.size()));
            uriSet.addAll(extractUriFromJs);
        }

        return uriSet;
    }


    /**
     * 判断提取的敏感信息是否都为空值
     * @param analyseInfo
     * @return
     */
    public static boolean analyseInfoIsNotEmpty(JSONObject analyseInfo) {
        return analyseInfo.getJSONArray(URL_KEY).size()>0
                || analyseInfo.getJSONArray(PATH_KEY).size()>0
                || analyseInfo.getJSONArray(INFO_KEY).size()>0;
    }

}
