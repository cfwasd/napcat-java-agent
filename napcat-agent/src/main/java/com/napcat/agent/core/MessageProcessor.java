package com.napcat.agent.core;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 消息预处理管道。
 */
@Slf4j
public class MessageProcessor {

    private static final Pattern IMAGE_URL_PATTERN = Pattern.compile(
            "(https?://[^\\s]+\\.(?:jpg|jpeg|png|gif|webp|bmp)(?:\\?[^\\s]*)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BASE64_IMAGE_PATTERN = Pattern.compile(
            "\\[图片:data:image/[^;]+;base64,[^\\]]+]", Pattern.CASE_INSENSITIVE);
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile(
            "!\\[[^\\]]*\\]\\(([^)]+)\\)");

    /**
     * 提取消息中的图片 URL（包括 base64 图片标记）
     */
    public List<String> extractImageUrls(String text) {
        List<String> urls = new ArrayList<>();
        Matcher m = IMAGE_URL_PATTERN.matcher(text);
        while (m.find()) urls.add(m.group(1));
        Matcher bm = BASE64_IMAGE_PATTERN.matcher(text);
        while (bm.find()) urls.add(bm.group());
        Matcher mm = MARKDOWN_IMAGE.matcher(text);
        while (mm.find()) {
            String url = mm.group(1).trim();
            if (url.startsWith("http")) urls.add(url);
        }
        return urls;
    }

    /**
     * 清理消息文本（去掉多余空白等）
     */
    public String cleanText(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s+", " ").trim();
    }
}
