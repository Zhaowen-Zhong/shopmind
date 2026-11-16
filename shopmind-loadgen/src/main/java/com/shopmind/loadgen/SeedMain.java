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
 *
 * <p>命名与定价按真实电商口径设计：每个类目只放该领域真实在售的品牌机型与合理价格带，
 * 版本档位（青春版/标准版/套装版/旗舰版）只影响价格，不再写进营销标题；
 * 内部型号（如“小-1001”）仅用于保证库存条目唯一，展示层会去除。</p>
 */
public final class SeedMain {

    /** 单品属性（进入卖点）。 */
    private static final String[] ATTRIBUTES = {
            "长续航", "低延迟", "高刷新率", "主动降噪", "轻量化", "静音设计",
            "快充支持", "多设备切换", "人体工学", "智能联动", "高色域", "防尘防水"
    };

    /** 使用场景（进入卖点）。 */
    private static final String[] SCENES = {
            "适合长时间办公", "适合游戏电竞", "适合通勤出差", "适合家庭使用",
            "适合学生党", "适合内容创作", "适合会议室", "适合户外运动"
    };

    /** 版本档位：不展示，仅作为价格调整因子。 */
    private static final String[] VERSIONS = {
            "标准版", "套装版", "旗舰版", "青春版"
    };

    /** 类目规格：名称、细分品类、真实品牌机型（品牌 + 系列）、价格带（元）。 */
    private record CatSpec(String name, String[] subtypes, String[][] models, int low, int high) {
    }

    private static final CatSpec[] SPECS = {
            new CatSpec("手机",
                    new String[]{"智能手机", "5G 手机", "旗舰手机", "全面屏手机", "拍照手机"},
                    new String[][]{{"小米", "14 Pro"}, {"小米", "14 Ultra"}, {"华为", "Mate 60 Pro"},
                            {"华为", "Pura 70 Ultra"}, {"OPPO", "Find X7 Ultra"}, {"vivo", "X100 Pro"},
                            {"三星", "Galaxy S24 Ultra"}, {"荣耀", "Magic6 Pro"}},
                    2299, 8999),
            new CatSpec("耳机",
                    new String[]{"蓝牙耳机", "降噪耳机", "头戴式耳机", "入耳式耳机", "真无线耳机"},
                    new String[][]{{"索尼", "WH-1000XM5"}, {"索尼", "WF-1000XM5"}, {"苹果", "AirPods Pro 2"},
                            {"华为", "FreeBuds Pro 3"}, {"漫步者", "NeoBuds Pro 2"}, {"Bose", "QuietComfort Ultra"},
                            {"小米", "Buds 4 Pro"}, {"安克", "Soundcore Liberty 4"}},
                    299, 2499),
            new CatSpec("键盘",
                    new String[]{"机械键盘", "无线键盘", "静音键盘", "办公键盘", "客制化键盘"},
                    new String[][]{{"罗技", "MX Mechanical"}, {"罗技", "G913 TKL"}, {"雷蛇", "BlackWidow V4"},
                            {"樱桃", "MX 3.0S"}, {"海盗船", "K70 RGB Pro"}, {"机械师", "K600"},
                            {"斐尔可", "Majestouch 2"}, {"阿米洛", "V2 风信子"}},
                    149, 1399),
            new CatSpec("鼠标",
                    new String[]{"无线鼠标", "游戏鼠标", "静音鼠标", "人体工学鼠标", "办公鼠标"},
                    new String[][]{{"罗技", "MX Master 3S"}, {"罗技", "G502 Hero"}, {"雷蛇", "DeathAdder V3"},
                            {"雷蛇", "Basilisk V3"}, {"微软", "Pro IntelliMouse"}, {"罗技", "G Pro X Superlight"},
                            {"赛睿", "Rival 7"}, {"达尔优", "A950"}},
                    79, 999),
            new CatSpec("显示器",
                    new String[]{"电竞显示器", "曲面显示器", "护眼显示器", "便携显示器", "4K 显示器"},
                    new String[][]{{"戴尔", "U2723QE"}, {"LG", "27GP95R"}, {"AOC", "U27G3X"},
                            {"三星", "Odyssey G8"}, {"飞利浦", "27E1N5900"}, {"华硕", "ROG PG27AQN"},
                            {"宏碁", "暗影骑士 XV275K"}, {"明基", "PD2705U"}},
                    899, 6499),
            new CatSpec("笔记本",
                    new String[]{"轻薄笔记本", "游戏笔记本", "商务笔记本", "二合一笔记本", "全能笔记本"},
                    new String[][]{{"联想", "ThinkPad X1 Carbon"}, {"联想", "拯救者 Y9000P"}, {"华为", "MateBook X Pro"},
                            {"戴尔", "XPS 15"}, {"苹果", "MacBook Pro 14"}, {"华硕", "灵耀 14"},
                            {"惠普", "战 X"}, {"荣耀", "MagicBook Pro 16"}},
                    4499, 14999),
            new CatSpec("音箱",
                    new String[]{"蓝牙音箱", "桌面音箱", "便携音箱", "智能音箱", "多媒体音箱"},
                    new String[][]{{"哈曼卡顿", "琉璃 3"}, {"JBL", "Charge 5"}, {"索尼", "SRS-XB100"},
                            {"小米", "小爱音箱 Pro"}, {"漫步者", "R1700BT"}, {"天猫精灵", "方糖 2"},
                            {"华为", "Sound X"}, {"Bose", "SoundLink Revolve"}},
                    129, 2299),
            new CatSpec("充电器",
                    new String[]{"氮化镓充电器", "快充充电器", "车载充电器", "多口充电器", "磁吸充电器"},
                    new String[][]{{"安克", "737 GaN"}, {"安克", "65W Nano 3"}, {"绿联", "100W GaN"},
                            {"倍思", "65W GaN"}, {"小米", "67W 快充"}, {"努比亚", "红魔 80W"},
                            {"品胜", "30W 氮化镓"}, {"IDMIX", "140W 充电器"}},
                    39, 499),
            new CatSpec("摄像头",
                    new String[]{"网络摄像头", "高清摄像头", "直播摄像头", "会议摄像头", "4K 摄像头"},
                    new String[][]{{"罗技", "C920 Pro"}, {"罗技", "Brio 4K"}, {"雷蛇", "Kiyo Pro"},
                            {"雷蛇", "Kiyo X"}, {"海康威视", "DS-E12"}, {"奥尼", "A31"},
                            {"明基", "ideaCam S1"}, {"安克", "PowerConf C200"}},
                    149, 1999),
            new CatSpec("扫地机器人",
                    new String[]{"扫拖一体机器人", "智能扫地机器人", "自清洁机器人", "全自动机器人", "无线洗地机"},
                    new String[][]{{"石头", "G20"}, {"科沃斯", "T20 Pro"}, {"追觅", "X30 Ultra"},
                            {"小米", "全能 2 Pro"}, {"海尔", "全能 T20"}, {"美的", "W11"},
                            {"云鲸", "J4"}, {"添可", "芙万 3.0"}},
                    1299, 5999),
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

    /** 各类目均衡生成：按 id 顺序在十个类目间均摊，组合维度不重复。 */
    public static List<ProductDoc> generate(int count) {
        List<ProductDoc> docs = new ArrayList<>(count);
        int perCat = count / SPECS.length;
        int remainder = count % SPECS.length;
        int index = 0;

        for (int c = 0; c < SPECS.length; c++) {
            CatSpec spec = SPECS[c];
            int target = perCat + (c < remainder ? 1 : 0);

            int nModels = spec.models.length;
            int nSub = spec.subtypes.length;
            int nAttr = ATTRIBUTES.length;
            int nScene = SCENES.length;
            int nVer = VERSIONS.length;
            int total = nModels * nSub * nAttr * nScene * nVer;

            for (int k = 0; k < target; k++) {
                int code = k % total;
                int vi = code % nVer; code /= nVer;
                int si = code % nScene; code /= nScene;
                int ai = code % nAttr; code /= nAttr;
                int subi = code % nSub; code /= nSub;
                int mi = code % nModels;

                String brand = spec.models[mi][0];
                String series = spec.models[mi][1];
                String version = VERSIONS[vi];
                int price = priceFor(spec, mi, version);

                index++;
                String internal = String.format("%s-%04d", brand.substring(0, 1), index % 9000 + 1000);
                String title = brand + " " + series + " " + internal;
                String description = spec.subtypes[subi] + "，" + version + "，" + ATTRIBUTES[ai] + "，"
                        + SCENES[si] + "，支持保修两年，参考价 " + price + " 元";
                docs.add(new ProductDoc(index, title, description, spec.name));
            }
        }
        return docs;
    }

    /** 机型在列表中的位置决定基础价，再叠加版本档位，最后取“9”结尾的常见定价。 */
    private static int priceFor(CatSpec spec, int modelIndex, String version) {
        double position = (modelIndex + 1.0) / spec.models.length;
        int base = spec.low + (int) Math.round(position * (spec.high - spec.low));
        double multiplier = switch (version) {
            case "旗舰版" -> 1.12;
            case "套装版" -> 1.06;
            case "青春版" -> 0.86;
            default -> 1.0;
        };
        int price = (int) Math.round(base * multiplier);
        return (price / 10) * 10 - 1;
    }
}
