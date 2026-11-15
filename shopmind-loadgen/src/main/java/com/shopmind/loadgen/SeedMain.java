package com.shopmind.loadgen;

import com.shopmind.service.model.ProductDoc;
import com.shopmind.service.store.KnowledgeBase;

import java.util.ArrayList;
import java.util.List;

/**
 * 合成商品语料生成器。
 *
 * <p>为什么用合成语料：公开商品数据集（如 Amazon Reviews 子集）下载体积大、许可条款各异，
 * 评审老师在离线环境里未必能取到。合成语料可控、可复现、可扩到十万级，
 * 适合压测"索引结构与网络层"；而<b>召回质量</b>的评测另用真实公开语料的小规模子集，
 * 两者在报告中分别标注口径，不混用。</p>
 */
public final class SeedMain {

    private static final String[] BRANDS = {
            "小米", "华为", "联想", "戴尔", "索尼", "飞利浦", "美的", "海尔", "罗技", "雷蛇",
            "安克", "绿联", "漫步者", "科大讯飞", "佳能", "尼康", "格力", "松下", "三星", "OPPO"
    };

    private static final String[][] CATEGORY_TEMPLATES = {
            {"手机", "智能手机", "旗舰手机", "全面屏手机", "5G 手机"},
            {"耳机", "蓝牙耳机", "降噪耳机", "入耳式耳机", "头戴式耳机"},
            {"键盘", "机械键盘", "无线键盘", "静音键盘", "办公键盘"},
            {"鼠标", "无线鼠标", "游戏鼠标", "静音鼠标", "人体工学鼠标"},
            {"显示器", "电竞显示器", "曲面显示器", "护眼显示器", "便携显示器"},
            {"笔记本", "轻薄笔记本", "游戏笔记本", "商务笔记本", "二合一笔记本"},
            {"音箱", "蓝牙音箱", "桌面音箱", "便携音箱", "智能音箱"},
            {"充电器", "氮化镓充电器", "快充充电器", "车载充电器", "多口充电器"},
            {"摄像头", "网络摄像头", "高清摄像头", "直播摄像头", "会议摄像头"},
            {"扫地机器人", "扫拖一体机器人", "智能扫地机器人", "无线吸尘器", "除螨仪"}
    };

    private static final String[] ATTRIBUTES = {
            "长续航", "低延迟", "高刷新率", "主动降噪", "轻量化", "静音设计",
            "快充支持", "多设备切换", "人体工学", "智能联动", "高色域", "防尘防水"
    };

    private static final String[] SCENES = {
            "适合长时间办公", "适合游戏电竞", "适合通勤出差", "适合家庭使用",
            "适合学生党", "适合内容创作", "适合会议室", "适合户外运动"
    };

    private static final String[] VERSIONS = {
            "标准版", "套装版", "旗舰版", "青春版"
    };

    private SeedMain() {
    }

    public static void main(String[] args) {
        int count = args.length > 0 ? Integer.parseInt(args[0]) : 20000;
        String path = args.length > 1 ? args[1] : "./data/shopmind";

        KnowledgeBase knowledgeBase = KnowledgeBase.file(path);
        long start = System.currentTimeMillis();
        List<ProductDoc> docs = generate(count);
        knowledgeBase.saveAll(docs);
        knowledgeBase.close();
        System.out.printf("完成：写入 %d 条，耗时 %d ms%n", docs.size(), System.currentTimeMillis() - start);
    }

    /** 生成不重复的商品文档：品牌 × 品类 × 属性 × 场景 × 型号尾号组合。 */
    public static List<ProductDoc> generate(int count) {
        List<ProductDoc> docs = new ArrayList<>(count);
        int index = 0;
        outer:
        for (String brand : BRANDS) {
            for (String[] categoryGroup : CATEGORY_TEMPLATES) {
                for (String categoryName : categoryGroup) {
                    for (String attribute : ATTRIBUTES) {
                        for (String scene : SCENES) {
                            for (String version : VERSIONS) {
                                if (docs.size() >= count) {
                                    break outer;
                                }
                                index++;
                                String model = String.format("%s-%04d", brand.substring(0, 1), index % 9000 + 1000);
                                String title = brand + " " + categoryName + " " + version + " " + model;
                                String description = categoryName + "，" + version + "，" + attribute + "，" + scene
                                        + "，支持保修两年，参考价 " + (99 + (index % 60) * 50) + " 元";
                                docs.add(new ProductDoc(index, title, description,
                                        categoryGroup[0]));
                            }
                        }
                    }
                }
            }
        }
        return docs;
    }
}
