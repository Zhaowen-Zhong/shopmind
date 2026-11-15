package com.shopmind.web;

import com.shopmind.service.model.ProductDoc;
import com.shopmind.service.retrieval.RetrievalService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 页面控制器（服务端渲染）。
 */
@Controller
public class PageController {

    private final RetrievalService retrievalService;

    public PageController(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("stats", retrievalService.stats());
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
}
