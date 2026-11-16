package com.shopmind.web;

import com.shopmind.gateway.net.GatewayServer;
import com.shopmind.index.IndexKind;
import com.shopmind.index.Neighbor;
import com.shopmind.index.SearchParams;
import com.shopmind.service.model.ProductDoc;
import com.shopmind.service.retrieval.RetrievalService;
import com.shopmind.service.retrieval.SearchOutcome;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * 面向页面的 REST 接口与流式导购。
 *
 * <p>HTTP 接口只承担"页面取数"和"管理操作"，高并发的检索长连接走 Netty 网关——
 * 这是本项目对两种网络编程模型的分工：HTTP 求通用，自研协议求紧凑与可控。</p>
 */
@RestController
@RequestMapping("/api")
public class SearchApiController {

    private final RetrievalService retrievalService;
    private final GatewayServer gatewayServer;
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private static final Pattern INTERNAL_CODE =
            Pattern.compile("\\s*[A-Za-z\\u4e00-\\u9fa5]-\\d+\\s*$");

    /** 十个一级类目名。 */
    private static final String[] CATEGORY_NAMES = {
            "手机", "耳机", "键盘", "鼠标", "显示器", "笔记本", "音箱", "充电器", "摄像头", "扫地机器人"
    };

    /** 需求关键词 → 需求点（属性 / 场景），用于“需求理解”。 */
    private static final String[][] NEED_KEYWORDS = {
            {"降噪", "主动降噪"}, {"续航", "长续航"}, {"轻薄", "轻量化"}, {"轻便", "轻量化"},
            {"静音", "静音设计"}, {"高刷", "高刷新率"}, {"快充", "快充支持"}, {"工学", "人体工学"},
            {"防水", "防尘防水"}, {"延迟", "低延迟"}, {"色域", "高色域"}, {"多设备", "多设备切换"},
            {"联动", "智能联动"}, {"办公", "适合长时间办公"}, {"游戏", "适合游戏电竞"}, {"电竞", "适合游戏电竞"},
            {"通勤", "适合通勤出差"}, {"出差", "适合通勤出差"}, {"便携", "适合通勤出差"},
            {"家庭", "适合家庭使用"}, {"家用", "适合家庭使用"}, {"学生", "适合学生党"},
            {"创作", "适合内容创作"}, {"设计", "适合内容创作"}, {"直播", "适合内容创作"},
            {"会议", "适合会议室"}, {"户外", "适合户外运动"}, {"运动", "适合户外运动"}
    };

    public SearchApiController(RetrievalService retrievalService, GatewayServer gatewayServer) {
        this.retrievalService = retrievalService;
        this.gatewayServer = gatewayServer;
    }

    /** 页面顶部指标卡：文档数、索引规模、网关连接与请求量。 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>(retrievalService.stats());
        Map<String, Object> gateway = new LinkedHashMap<>();
        gateway.put("activeConnections", gatewayServer.metrics().activeConnections());
        gateway.put("totalConnections", gatewayServer.metrics().totalConnections());
        gateway.put("totalSearches", gatewayServer.metrics().totalSearches());
        gateway.put("rejectedByLimit", gatewayServer.metrics().rejectedByLimit());
        gateway.put("unauthorized", gatewayServer.metrics().unauthorizedCount());
        gateway.put("port", gatewayServer.port());
        result.put("gateway", gateway);
        return result;
    }

    /** 单索引检索。 */
    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody SearchQuery query) {
        IndexKind kind = parseKind(query.kind());
        SearchParams params = new SearchParams(query.topK(), query.efSearch(), 8);
        SearchOutcome outcome = retrievalService.search(kind, query.query(), params, query.alpha());
        return describe(outcome);
    }

    /** 三索引并排检索，供调试台做对照。 */
    @PostMapping("/search-all")
    public Map<String, Object> searchAll(@RequestBody SearchQuery query) {
        SearchParams params = new SearchParams(query.topK(), query.efSearch(), 8);
        Map<IndexKind, SearchOutcome> outcomes =
                retrievalService.searchAll(query.query(), params, query.alpha());
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<IndexKind, SearchOutcome> entry : outcomes.entrySet()) {
            result.put(entry.getKey().label(), describe(entry.getValue()));
        }
        return result;
    }

    @GetMapping("/docs")
    public List<ProductDoc> docs(@RequestParam(defaultValue = "0") int offset,
                                 @RequestParam(defaultValue = "20") int limit) {
        return retrievalService.listDocuments(offset, limit);
    }

    @PostMapping("/docs")
    public Map<String, Object> addDoc(@RequestBody ProductDoc doc) {
        long id = doc.id() > 0 ? doc.id() : nextId();
        long elapsedMicros = retrievalService.addDocument(
                new ProductDoc(id, doc.title(), doc.description(), doc.category())) / 1000;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("elapsedMicros", elapsedMicros);
        return result;
    }

    @DeleteMapping("/docs/{id}")
    public Map<String, Object> deleteDoc(@PathVariable long id) {
        boolean removed = retrievalService.deleteDocument(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("removed", removed);
        return result;
    }

    @PostMapping("/rebuild")
    public Map<String, Object> rebuild() {
        long start = System.currentTimeMillis();
        retrievalService.rebuildFromStore();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("elapsedMillis", System.currentTimeMillis() - start);
        result.put("stats", retrievalService.stats());
        return result;
    }

    /**
     * 流式导购：先检索，再按模板组织回答并分段推送。
     *
     * <p>说明：本次实现不含大模型生成，回答由检索结果按固定话术组织（即 RAG 的"检索 + 模板拼装"部分），
     * 这样评测可量化、结果可复现；接大模型只需替换 {@code composeAnswer} 的内部实现。</p>
     */
    @GetMapping(value = "/guide", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter guide(@RequestParam String q) {
        SseEmitter emitter = new SseEmitter(30_000L);
        streamExecutor.submit(() -> {
            try {
                long start = System.nanoTime();
                // 取较宽候选，再做类目过滤与同名去重，收敛到 5 个
                SearchParams params = new SearchParams(25, SearchParams.DEFAULT_EF_SEARCH, 8);
                SearchOutcome outcome = retrievalService.search(IndexKind.HNSW, q, params, 0.8);
                List<Neighbor> curated = curateHits(q, outcome);

                // 第一阶段：智能思考（推理过程），用 think 事件单独推送
                List<String> thinking = buildThinking(q, curated);
                for (String line : thinking) {
                    emitter.send(SseEmitter.event().name("think")
                            .data(line, MediaType.TEXT_PLAIN));
                    Thread.sleep(24);
                }
                // 第二阶段：正式回答（客户视角的简洁结论）
                List<String> answer = buildAnswer(q, curated);
                for (String line : answer) {
                    emitter.send(SseEmitter.event().name("chunk")
                            .data(line, MediaType.TEXT_PLAIN));
                    Thread.sleep(30);
                }
                double latencyMicros = (System.nanoTime() - start) / 1000.0;
                emitter.send(SseEmitter.event().name("done")
                        .data(String.format("{\"latencyMicros\":%.1f}", latencyMicros),
                                MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    /** 智能思考：需求理解、筛选思路与逐商品对照的推理过程（前端折叠展示）。 */
    private List<String> buildThinking(String query, List<Neighbor> hits) {
        List<String> lines = new ArrayList<>();
        if (hits.isEmpty()) {
            return lines;
        }
        List<String> needs = detectNeeds(query);
        String category = detectCategory(query);

        lines.add("先拆解需求。");
        if (category != null && !needs.isEmpty()) {
            lines.add("用户想找「" + category + "」，看重：" + String.join("、", needs) + "。");
        } else if (category != null) {
            lines.add("用户想找「" + category + "」，未指定具体偏好，按整体匹配度筛选。");
        } else if (!needs.isEmpty()) {
            lines.add("用户主要看重：" + String.join("、", needs) + "。");
        } else {
            lines.add("用户未指定具体偏好，按语义相关度与关键词匹配综合筛选。");
        }
        lines.add("筛选思路：向量语义检索理解意图，关键词匹配校正，再按类目过滤、同型号去重，最后核对卖点。");

        for (int i = 0; i < hits.size(); i++) {
            Neighbor hit = hits.get(i);
            ProductDoc doc = retrievalService.findDocument(hit.docId()).orElse(null);
            StringBuilder line = new StringBuilder(String.format(
                    "对照候选 %d（匹配度 %.0f%%）", i + 1, hit.score() * 100));
            if (doc != null) {
                String[] points = docPoints(doc.description());
                if (points.length > 0) {
                    line.append("：").append(String.join("、", points));
                    String matched = firstMatch(points, needs);
                    if (matched != null) {
                        line.append("，命中需求「").append(matched).append("」");
                    }
                }
            }
            line.append("。");
            lines.add(line.toString());
        }
        return lines;
    }

    /** 正式回答：客户视角的简洁结论。 */
    private List<String> buildAnswer(String query, List<Neighbor> hits) {
        List<String> lines = new ArrayList<>();
        if (hits.isEmpty()) {
            lines.add("抱歉，我暂时没找到和「" + query + "」对得上的商品。");
            lines.add("你可以换个说法，比如带上具体品类或使用场景，我再帮你找找。");
            return lines;
        }
        lines.add("为你挑了这几款，按和你需求的匹配度排序：");
        for (int i = 0; i < hits.size(); i++) {
            Neighbor hit = hits.get(i);
            ProductDoc doc = retrievalService.findDocument(hit.docId()).orElse(null);
            String name = doc == null ? "商品 " + hit.docId() : friendlyTitle(doc.title());
            lines.add(String.format("%d. %s（匹配度约 %.0f%%）", i + 1, name, hit.score() * 100));
        }
        lines.add("其中第 1 款最贴合你的需求。告诉我预算或更在意的点，我可以再帮你缩小范围。");
        return lines;
    }

    /**
     * 候选收敛：当问句显式提到类目时做元数据过滤，并按友好名称去除同型号重复项，
     * 最多保留 5 个；若过滤后为空则回退为仅去重，保证一定有结果。
     */
    private List<Neighbor> curateHits(String query, SearchOutcome outcome) {
        String category = detectCategory(query);
        Set<String> seen = new HashSet<>();
        List<Neighbor> curated = new ArrayList<>();
        for (Neighbor hit : outcome.hits()) {
            ProductDoc doc = retrievalService.findDocument(hit.docId()).orElse(null);
            if (category != null && (doc == null || !category.equals(doc.category()))) {
                continue;
            }
            String name = doc == null ? "id" + hit.docId() : friendlyTitle(doc.title());
            if (seen.add(name)) {
                curated.add(hit);
                if (curated.size() >= 5) {
                    break;
                }
            }
        }
        if (curated.isEmpty()) {
            for (Neighbor hit : outcome.hits()) {
                ProductDoc doc = retrievalService.findDocument(hit.docId()).orElse(null);
                String name = doc == null ? "id" + hit.docId() : friendlyTitle(doc.title());
                if (seen.add(name)) {
                    curated.add(hit);
                    if (curated.size() >= 5) {
                        break;
                    }
                }
            }
        }
        return curated;
    }

    /** 从问句中识别属性 / 场景需求，返回自然语言短语。 */
    private List<String> detectNeeds(String query) {
        List<String> found = new ArrayList<>();
        for (String[] pair : NEED_KEYWORDS) {
            if (query.contains(pair[0]) && !found.contains(pair[1])) {
                found.add(pair[1]);
            }
        }
        return found;
    }

    private String detectCategory(String query) {
        for (String name : CATEGORY_NAMES) {
            if (query.contains(name)) {
                return name;
            }
        }
        return null;
    }

    /** 从描述中取出该商品的属性与场景卖点。 */
    private String[] docPoints(String description) {
        List<String> points = new ArrayList<>();
        if (description == null) {
            return new String[0];
        }
        String[] parts = description.split("，");
        for (int i = 2; i < parts.length; i++) {
            String p = parts[i].trim();
            if (!p.isBlank() && !p.startsWith("参考价") && !p.contains("保修")) {
                points.add(p);
            }
        }
        return points.toArray(new String[0]);
    }

    private String firstMatch(String[] points, List<String> needs) {
        for (String need : needs) {
            for (String p : points) {
                if (p.contains(need) || need.contains(p)) {
                    return need;
                }
            }
        }
        return null;
    }

    /** 去掉标题末尾的内部型号，只保留品牌 + 系列的营销名称。 */
    private String friendlyTitle(String raw) {
        if (raw == null) {
            return "";
        }
        return INTERNAL_CODE.matcher(raw).replaceAll("").trim();
    }

    private Map<String, Object> describe(SearchOutcome outcome) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("index", outcome.kind().label());
        result.put("latencyMicros", Math.round(outcome.latencyMicros() * 100) / 100.0);
        List<Map<String, Object>> hits = new ArrayList<>(outcome.hits().size());
        for (Neighbor neighbor : outcome.hits()) {
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("id", neighbor.docId());
            hit.put("title", retrievalService.titleOf(neighbor.docId()));
            hit.put("score", Math.round(neighbor.score() * 10000) / 10000.0);
            hits.add(hit);
        }
        result.put("hits", hits);
        return result;
    }

    private long nextId() {
        List<ProductDoc> all = retrievalService.listDocuments(0, Integer.MAX_VALUE);
        long max = 0;
        for (ProductDoc doc : all) {
            max = Math.max(max, doc.id());
        }
        return max + 1;
    }

    private IndexKind parseKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return IndexKind.HNSW;
        }
        return switch (kind.toLowerCase()) {
            case "brute-force", "brute" -> IndexKind.BRUTE_FORCE;
            case "ivf" -> IndexKind.IVF;
            default -> IndexKind.HNSW;
        };
    }

    /** 页面检索入参；alpha 为混合权重，1.0 表示纯向量。 */
    public record SearchQuery(String query, String kind, int topK, int efSearch, double alpha) {
        public SearchQuery {
            if (topK <= 0) {
                topK = 10;
            }
            if (efSearch < topK) {
                efSearch = Math.max(SearchParams.DEFAULT_EF_SEARCH, topK);
            }
            if (alpha <= 0 || alpha > 1) {
                alpha = 0.8;
            }
        }
    }
}
