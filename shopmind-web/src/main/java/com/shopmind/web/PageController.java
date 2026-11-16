package com.shopmind.web;

import com.shopmind.gateway.net.GatewayMetrics;
import com.shopmind.gateway.net.GatewayServer;
import com.shopmind.service.model.ProductDoc;
import com.shopmind.service.retrieval.RetrievalService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 页面控制器（服务端渲染）。
 */
@Controller
public class PageController {

    private final RetrievalService retrievalService;
    private final GatewayServer gatewayServer;

    public PageController(RetrievalService retrievalService, GatewayServer gatewayServer) {
        this.retrievalService = retrievalService;
        this.gatewayServer = gatewayServer;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        Map<String, Object> stats = retrievalService.stats();
        stats.put("gateway", gatewayStats());
        model.addAttribute("stats", stats);
        return "dashboard";
    }

    @GetMapping("/console")
    public String console(Model model) {
        model.addAttribute("stats", retrievalService.stats());
        return "console";
    }

    @GetMapping("/kb")
    public String knowledgeBase(@RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size,
                                Model model) {
        List<ProductDoc> docs = retrievalService.listDocuments(page * size, size);
        model.addAttribute("docs", docs);
        model.addAttribute("page", page);
        model.addAttribute("size", size);
        model.addAttribute("total", retrievalService.documentCount());
        return "kb";
    }

    @GetMapping("/guide")
    public String guide(Model model) {
        model.addAttribute("stats", retrievalService.stats());
        return "guide";
    }

    /** 组装网关运行指标，字段名与 dashboard 模板中的 stats['gateway'] 对齐。 */
    private Map<String, Object> gatewayStats() {
        GatewayMetrics metrics = gatewayServer.metrics();
        Map<String, Object> gateway = new LinkedHashMap<>();
        gateway.put("port", gatewayServer.port());
        gateway.put("activeConnections", metrics.activeConnections());
        gateway.put("totalConnections", metrics.totalConnections());
        gateway.put("totalSearches", metrics.totalSearches());
        gateway.put("rejectedByLimit", metrics.rejectedByLimit());
        gateway.put("unauthorized", metrics.unauthorizedCount());
        return gateway;
    }
}
