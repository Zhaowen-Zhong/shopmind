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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
                SearchParams params = new SearchParams(5, SearchParams.DEFAULT_EF_SEARCH, 8);
                SearchOutcome outcome = retrievalService.search(IndexKind.HNSW, q, params, 0.8);
                List<String> sentences = composeAnswer(q, outcome);
                for (int i = 0; i < sentences.size(); i++) {
                    emitter.send(SseEmitter.event().name("chunk")
                            .data(sentences.get(i), MediaType.TEXT_PLAIN));
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

    private List<String> composeAnswer(String query, SearchOutcome outcome) {
        List<String> parts = new ArrayList<>();
        List<Neighbor> hits = outcome.hits();
        if (hits.isEmpty()) {
            parts.add("没有在知识库中找到与「" + query + "」相关的商品。");
            parts.add("建议：换用更具体的品类词，或先在知识库中录入对应商品。");
            return parts;
        }
        parts.add("围绕「" + query + "」为你找到 " + hits.size() + " 件相关商品。");
        for (int i = 0; i < hits.size(); i++) {
            Neighbor hit = hits.get(i);
            String title = retrievalService.titleOf(hit.docId());
            parts.add(String.format("%d. %s（相关度 %.0f%%）。", i + 1,
                    title.isEmpty() ? "文档 " + hit.docId() : title, hit.score() * 100));
        }
        parts.add("以上结果由 HNSW 向量检索与 BM25 关键词检索混合打分得到，可按相关度逐条查看详情。");
        return parts;
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
