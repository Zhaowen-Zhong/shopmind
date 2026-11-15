# ShopMind

Java 自研向量检索与商品知识库智能导购平台（《Java 语言及网络编程》课程项目）。

核心不是"用框架搭一个 RAG Demo"，而是**把检索内核与网络协议在 Java 里自己造出来**：
HNSW 分层近邻图索引、IVF 倒排聚类、BM25 关键词检索、混合打分，以及一套自定义二进制协议的 Netty 长连接网关。

## 模块

| 模块 | 职责 |
|---|---|
| `shopmind-common` | 自研协议帧、编解码器、错误码、向量距离计算、JSON 工具 |
| `shopmind-index` | 暴力检索（精确基准）、IVF、HNSW、BM25、混合打分、轻量分词 |
| `shopmind-service` | 知识库（H2）、TF-IDF 哈希向量化、检索编排、文档元数据 |
| `shopmind-gateway` | Netty 长连接网关：握手鉴权、心跳、令牌桶限流、检索分发 |
| `shopmind-web` | Spring Boot 应用：Thymeleaf 页面、REST、SSE 流式导购、监控看板 |
| `shopmind-loadgen` | 自研 Netty 压测客户端，输出 CSV 原始数据 |

## 快速开始

```bash
# 构建（需要 JDK 21；Maven 由 wrapper 自动准备）
./mvnw clean package -DskipTests

# 启动
java -jar shopmind-web/target/shopmind-web-1.0.0.jar
```

启动后：

- 页面：<http://localhost:8080>（监控看板 / 检索调试台 / 知识库 / 智能导购）
- 网关：`localhost:9099`，自研二进制协议（默认令牌 `shopmind-demo-token`）

## 自研协议帧

| 字段 | 长度 | 说明 |
|---|---|---|
| magic | 2 B | 固定 `0x534D` |
| version | 1 B | 协议版本 |
| cmd | 1 B | 握手 / 心跳 / 检索 / 流式 / 错误 |
| seq | 4 B | 序列号，请求-响应匹配 |
| length | 4 B | body 长度 |
| body | N B | 载荷 |

不带长度域拆包器：解码器自行判断"帧体是否到齐"，未到齐则回退读指针等待下次读取，因此天然支持半包与粘包。

## 指标与实验

- `docs/experiments/` 保存真实的实验输出（参数扫描、压测汇总），原始逐请求数据不入库。
- 关键结论均附运行环境与参数，任何人在同版本 JDK 上重跑可复现。

## 第三方依赖说明

本项目仅以 Maven 依赖方式使用 Spring Boot、Netty、Jackson、H2、SLF4J、JUnit 5；
检索算法（HNSW / IVF / BM25 / 混合打分）、协议编解码、网关、压测客户端均为自研代码。

## 许可

MIT，见 [LICENSE](LICENSE)。
