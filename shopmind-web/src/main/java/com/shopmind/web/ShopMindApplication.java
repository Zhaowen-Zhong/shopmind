package com.shopmind.web;

import com.shopmind.gateway.net.GatewayServer;
import com.shopmind.service.embedding.HashingTfIdfEmbedder;
import com.shopmind.service.retrieval.RetrievalService;
import com.shopmind.service.store.KnowledgeBase;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * ShopMind 启动类。
 *
 * <p>同一个进程里跑两套网络服务：Spring 的 HTTP（页面与 REST），以及自研 Netty 网关（二进制协议长连接）。
 * 这样演示时只需 java -jar 一条命令，两个端口同时可用，报告里的"部署说明"才不会变成一堆前置条件。</p>
 */
@SpringBootApplication
public class ShopMindApplication {

    private static final Logger log = LoggerFactory.getLogger(ShopMindApplication.class);

    @Value("${shopmind.gateway.port:9099}")
    private int gatewayPort;

    @Value("${shopmind.gateway.token:shopmind-demo-token}")
    private String gatewayToken;

    @Value("${shopmind.data.path:./data/shopmind}")
    private String dataPath;

    public static void main(String[] args) {
        SpringApplication.run(ShopMindApplication.class, args);
    }

    @Bean
    public KnowledgeBase knowledgeBase() {
        return KnowledgeBase.file(dataPath);
    }

    @Bean
    public RetrievalService retrievalService(KnowledgeBase knowledgeBase) {
        RetrievalService service = new RetrievalService(knowledgeBase, new HashingTfIdfEmbedder(128));
        if (knowledgeBase.count() > 0) {
            service.rebuildFromStore();
        }
        return service;
    }

    @Bean
    public GatewayServer gatewayServer(RetrievalService retrievalService) throws InterruptedException {
        GatewayServer server = new GatewayServer(gatewayPort, retrievalService, gatewayToken).start();
        log.info("自研网关于端口 {} 启动完成，令牌已配置", server.port());
        return server;
    }

    @PreDestroy
    public void shutdown() {
        log.info("ShopMind 正在关闭");
    }
}
