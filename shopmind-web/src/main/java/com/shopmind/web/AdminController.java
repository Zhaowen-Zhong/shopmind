package com.shopmind.web;

import com.shopmind.gateway.net.GatewayServer;
import com.shopmind.service.retrieval.RetrievalService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对内运营后台：监控看板、检索调试台与知识库管理，统一收纳到 /admin 前缀。
 *
 * <p>这里保留工程视角的完整指标与管理操作，与客户视角的 {@link StorefrontController}
 * 使用各自独立的导航，互不干扰。</p>
 */
@Controller
public class AdminController {

    private final RetrievalService retrievalService;
    private final GatewayServer gatewayServer;

    public AdminController(RetrievalService retrievalService, GatewayServer gatewayServer) {
        this.retrievalService = retrievalService;
        this.gatewayServer = gatewayServer;
    }

    /** 系统监控看板：检索服务、索引与网关状态。 */
    @GetMapping("/admin/dashboard")
    public String dashboard(Model model) {
        Map<String, Object> stats = new LinkedHashMap<>(retrievalService.stats());
        stats.put("gateway", gatewayStats());
        model.addAttribute("stats", stats);
        return "admin/dashboard";
    }

    /** 检索调试台：取数由页面内 JS 调用 /api/search-all。 */
    @GetMapping("/admin/console")
    public String console() {
        return "admin/console";
    }

    /** 知识库管理：分页展示文档。 */
    @GetMapping("/admin/kb")
    public String kb(@RequestParam(defaultValue = "0") int page,
                     @RequestParam(defaultValue = "20") int size,
                     Model model) {
        model.addAttribute("docs", retrievalService.listDocuments(page * size, size));
        model.addAttribute("total", retrievalService.documentCount());
        model.addAttribute("page", page);
        model.addAttribute("size", size);
        return "admin/kb";
    }

    private Map<String, Object> gatewayStats() {
        Map<String, Object> gateway = new LinkedHashMap<>();
        gateway.put("port", gatewayServer.port());
        gateway.put("activeConnections", gatewayServer.metrics().activeConnections());
        gateway.put("totalConnections", gatewayServer.metrics().totalConnections());
        gateway.put("totalSearches", gatewayServer.metrics().totalSearches());
        gateway.put("rejectedByLimit", gatewayServer.metrics().rejectedByLimit());
        gateway.put("unauthorized", gatewayServer.metrics().unauthorizedCount());
        return gateway;
    }
}
