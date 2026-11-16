package com.shopmind.web;

import com.shopmind.index.IndexKind;
import com.shopmind.index.Neighbor;
import com.shopmind.index.SearchParams;
import com.shopmind.service.model.ProductDoc;
import com.shopmind.service.retrieval.RetrievalService;
import com.shopmind.service.retrieval.SearchOutcome;
import com.shopmind.service.store.KnowledgeBase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 面向客户的商业前台：品牌落地首页、商品检索结果页与智能导购。
 *
 * <p>与 {@link AdminController} 承载的内部工程视角相对，这里只呈现客户关心的内容：
 * 分类、商品卡片、相关度与参考价；检索能力复用同一个 {@link RetrievalService}。</p>
 */
@Controller
public class StorefrontController {

    /** 与 SeedMain 一致的一级类目顺序，序号决定缩略图配色。 */
    static final List<String> CATEGORIES = List.of(
            "手机", "耳机", "键盘", "鼠标", "显示器", "笔记本",
            "音箱", "充电器", "摄像头", "扫地机器人");

    private static final Pattern PRICE_PATTERN = Pattern.compile("参考价\\s*(\\d+)");
    private static final Pattern MODEL_PATTERN = Pattern.compile("\\s*[A-Za-z\\u4e00-\\u9fa5]-\\d+\\s*$");
    private static final int PAGE_WINDOW = 5;

    private final KnowledgeBase knowledgeBase;
    private final RetrievalService retrievalService;

    @Value("${shopmind.embedding.dimension:384}")
    private int dimension;

    public StorefrontController(KnowledgeBase knowledgeBase, RetrievalService retrievalService) {
        this.knowledgeBase = knowledgeBase;
        this.retrievalService = retrievalService;
    }

    /** 品牌落地首页：主视觉、分类入口与精选商品。 */
    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("total", knowledgeBase.count());
        model.addAttribute("totalDisplay",
                String.format("%,d", knowledgeBase.count()) + "+");
        model.addAttribute("categories", CATEGORIES);
        model.addAttribute("dimension", dimension);
        model.addAttribute("p99", "3.45");

        Map<String, Long> categoryCounts = new LinkedHashMap<>();
        for (String category : CATEGORIES) {
            categoryCounts.put(category, knowledgeBase.countByCategory(category));
        }
        model.addAttribute("categoryCounts", categoryCounts);

        List<ProductView> featured = new ArrayList<>();
        for (ProductDoc doc : knowledgeBase.list(0, 8)) {
            featured.add(toView(doc, null));
        }
        model.addAttribute("featured", featured);
        return "home";
    }

    /** 商品检索结果页：支持关键词检索、分类筛选与分页浏览。 */
    @GetMapping("/products")
    public String products(@RequestParam(required = false) String q,
                           @RequestParam(required = false) String category,
                           @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "12") int size,
                           Model model) {
        boolean searchMode = q != null && !q.isBlank();
        model.addAttribute("q", q);
        model.addAttribute("category", category);
        model.addAttribute("categories", CATEGORIES);
        model.addAttribute("searchMode", searchMode);
        model.addAttribute("page", page);
        model.addAttribute("size", size);

        List<ProductView> views = new ArrayList<>();

        if (searchMode) {
            SearchParams params = new SearchParams(size, SearchParams.DEFAULT_EF_SEARCH, 8);
            SearchOutcome outcome =
                    retrievalService.search(IndexKind.HNSW, q, params, 0.8);
            for (Neighbor hit : outcome.hits()) {
                knowledgeBase.findById(hit.docId()).ifPresent(doc ->
                        views.add(toView(doc, (double) hit.score())));
            }
            model.addAttribute("total", views.size());
        } else {
            boolean byCategory = category != null && !category.isBlank();
            long total = byCategory
                    ? knowledgeBase.countByCategory(category)
                    : knowledgeBase.count();
            List<ProductDoc> docs = byCategory
                    ? knowledgeBase.findByCategory(category, page * size, size)
                    : knowledgeBase.list(page * size, size);
            for (ProductDoc doc : docs) {
                views.add(toView(doc, null));
            }
            int totalPages = (int) Math.max(1, Math.ceil((double) total / size));
            model.addAttribute("total", total);
            model.addAttribute("totalPages", totalPages);
            model.addAttribute("pageNumbers", pageNumbers(page, totalPages));
        }

        model.addAttribute("products", views);
        return "products";
    }

    /** 精致版智能导购对话页（流式逻辑在页面内通过 SSE 调用 /api/guide）。 */
    @GetMapping("/guide")
    public String guide() {
        return "guide";
    }

    /* ---------- 私有辅助 ---------- */

    private ProductView toView(ProductDoc doc, Double score) {
        int index = Math.max(0, CATEGORIES.indexOf(doc.category()));
        return new ProductView(doc.id(), stripModel(doc.title()), doc.description(),
                doc.category(), extractPoints(doc.description()), score,
                parsePrice(doc.description()), index);
    }

    /** 去掉标题末尾的内部型号（如“小-1001”），保留品牌 + 品类 + 版本。 */
    private String stripModel(String title) {
        if (title == null) {
            return "";
        }
        return MODEL_PATTERN.matcher(title).replaceAll("").trim();
    }

    /** 从描述中提取差异化卖点（属性 / 场景），跳过品类、版本与固定话术。 */
    private List<String> extractPoints(String description) {
        List<String> points = new ArrayList<>();
        if (description == null) {
            return points;
        }
        String[] parts = description.split("，");
        for (int i = 0; i < parts.length && points.size() < 3; i++) {
            String part = parts[i].trim();
            if (i < 2 || part.isBlank() || part.startsWith("参考价") || part.contains("保修")) {
                continue;
            }
            points.add(part);
        }
        return points;
    }

    private String parsePrice(String description) {
        if (description == null) {
            return "—";
        }
        Matcher matcher = PRICE_PATTERN.matcher(description);
        return matcher.find() ? "¥" + matcher.group(1) : "—";
    }

    /** 以当前页为中心，生成最多 {@link #PAGE_WINDOW} 个页码（1 基）。 */
    private List<Integer> pageNumbers(int page, int totalPages) {
        int start = Math.max(0, page - PAGE_WINDOW / 2);
        int end = Math.min(totalPages, start + PAGE_WINDOW);
        start = Math.max(0, end - PAGE_WINDOW);
        List<Integer> numbers = new ArrayList<>();
        for (int i = start; i < end; i++) {
            numbers.add(i + 1);
        }
        return numbers;
    }
}
