package com.shopmind.index;

import java.util.ArrayList;
import java.util.List;

/**
 * 轻量分词器（无第三方分词库，纯 Java 实现）。
 *
 * <p>中文采用"单字 + 双字"切分：不用词典也能覆盖商品标题里的高频搭配，
 * 且不会因为未登录词而完全召回失败；英文与数字按连续字母数字串切分并小写化。
 * 这样 BM25 与向量检索面对的是同一份 token，便于混合打分对齐。</p>
 */
public final class TextTokenizer {

    private TextTokenizer() {
    }

    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        StringBuilder latin = new StringBuilder();
        List<Character> cjkRun = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                flushLatin(latin, tokens);
                cjkRun.add(c);
            } else if (Character.isLetterOrDigit(c)) {
                flushCjk(cjkRun, tokens);
                latin.append(Character.toLowerCase(c));
            } else {
                flushLatin(latin, tokens);
                flushCjk(cjkRun, tokens);
            }
        }
        flushLatin(latin, tokens);
        flushCjk(cjkRun, tokens);
        return tokens;
    }

    private static void flushLatin(StringBuilder latin, List<String> tokens) {
        if (latin.length() > 0) {
            tokens.add(latin.toString());
            latin.setLength(0);
        }
    }

    private static void flushCjk(List<Character> run, List<String> tokens) {
        int size = run.size();
        for (int i = 0; i < size; i++) {
            tokens.add(String.valueOf(run.get(i)));
        }
        for (int i = 0; i + 1 < size; i++) {
            tokens.add(new String(new char[]{run.get(i), run.get(i + 1)}));
        }
        run.clear();
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }
}
