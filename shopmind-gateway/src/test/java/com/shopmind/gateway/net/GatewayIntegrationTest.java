package com.shopmind.gateway.net;

import com.shopmind.common.json.JsonCodec;
import com.shopmind.common.protocol.CmdType;
import com.shopmind.common.protocol.Frame;
import com.shopmind.common.protocol.FrameCodec;
import com.shopmind.gateway.dto.HandshakeRequest;
import com.shopmind.gateway.dto.SearchRequest;
import com.shopmind.gateway.dto.SearchResponse;
import com.shopmind.index.IndexKind;
import com.shopmind.index.SearchParams;
import com.shopmind.service.model.ProductDoc;
import com.shopmind.service.retrieval.RetrievalService;
import com.shopmind.service.store.KnowledgeBase;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关端到端测试：真的起一个 Netty 服务端、真的用 Netty 客户端连上来，
 * 走完"握手 → 心跳 → 检索"三条路径，验证自研协议不只是能编解码，而是能承载业务。
 */
class GatewayIntegrationTest {

    private static final String TOKEN = "shopmind-test-token";

    private KnowledgeBase knowledgeBase;
    private RetrievalService retrieval;
    private GatewayServer server;
    private EventLoopGroup clientGroup;
    private Channel clientChannel;
    private final BlockingQueue<Frame> incoming = new ArrayBlockingQueue<>(16);

    @BeforeEach
    void setUp() throws Exception {
        knowledgeBase = KnowledgeBase.memory();
        retrieval = new RetrievalService(knowledgeBase);
        retrieval.addDocument(new ProductDoc(1L, "小米手机 13", "高性能旗舰手机，骁龙处理器", "手机"));
        retrieval.addDocument(new ProductDoc(2L, "机械键盘 87键", "青轴机械键盘，适合办公打字", "外设"));
        retrieval.addDocument(new ProductDoc(3L, "蓝牙耳机", "主动降噪蓝牙耳机，续航 30 小时", "音频"));

        server = new GatewayServer(0, retrieval, TOKEN).start();
    }

    @AfterEach
    void tearDown() {
        if (clientChannel != null) {
            clientChannel.close();
        }
        if (clientGroup != null) {
            clientGroup.shutdownGracefully();
        }
        server.close();
        knowledgeBase.close();
    }

    @Test
    void handshakeHeartbeatAndSearchShouldWork() throws Exception {
        connect();

        send(CmdType.HANDSHAKE, 1, JsonCodec.toJson(new HandshakeRequest(TOKEN)).getBytes(StandardCharsets.UTF_8));
        Frame ack = poll();
        assertEquals(CmdType.HANDSHAKE_ACK, ack.cmd(), "握手应返回 ACK");

        send(CmdType.HEARTBEAT, 2, new byte[0]);
        assertEquals(CmdType.HEARTBEAT_ACK, poll().cmd(), "心跳应返回 ACK");

        SearchRequest request = new SearchRequest("降噪耳机", 3, 100, "hnsw", 0.8);
        send(CmdType.SEARCH, 3, JsonCodec.toJson(request).getBytes(StandardCharsets.UTF_8));
        Frame response = poll();
        assertEquals(CmdType.SEARCH_RESULT, response.cmd());
        SearchResponse payload = JsonCodec.fromJson(response.body(), SearchResponse.class);
        assertFalse(payload.hits().isEmpty(), "检索应有命中");
        assertEquals("hnsw", payload.index());
        assertTrue(payload.hits().get(0).score() > 0.3f, "最相关结果分数应显著为正");

        assertEquals(1, server.metrics().activeConnections());
    }

    @Test
    void searchBeforeHandshakeShouldBeRejected() throws Exception {
        connect();
        send(CmdType.SEARCH, 9, JsonCodec.toJson(new SearchRequest("耳机", 5, 100, "hnsw", 1.0))
                .getBytes(StandardCharsets.UTF_8));
        Frame response = poll();
        assertEquals(CmdType.ERROR, response.cmd(), "未握手应先返回错误帧");
    }

    @Test
    void invalidTokenShouldBeRejectedAndClosed() throws Exception {
        connect();
        send(CmdType.HANDSHAKE, 1, JsonCodec.toJson(new HandshakeRequest("wrong")).getBytes(StandardCharsets.UTF_8));
        Frame response = poll();
        assertEquals(CmdType.ERROR, response.cmd());
        assertEquals(1, server.metrics().unauthorizedCount());
    }

    private void connect() throws InterruptedException {
        clientGroup = new NioEventLoopGroup(1);
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(clientGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new FrameCodec());
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<Frame>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, Frame msg) {
                                incoming.offer(msg);
                            }
                        });
                    }
                });
        clientChannel = bootstrap.connect("127.0.0.1", server.port()).sync().channel();
    }

    private void send(CmdType cmd, int seq, byte[] body) {
        clientChannel.writeAndFlush(FrameCodec.encode(clientChannel.alloc(), new Frame(cmd, seq, body)));
    }

    private Frame poll() throws InterruptedException {
        Frame frame = incoming.poll(5, TimeUnit.SECONDS);
        assertTrue(frame != null, "5 秒内应收到应答帧");
        return frame;
    }

    @Test
    void searchShouldRespectTopK() throws Exception {
        connect();
        send(CmdType.HANDSHAKE, 1, JsonCodec.toJson(new HandshakeRequest(TOKEN)).getBytes(StandardCharsets.UTF_8));
        poll();

        send(CmdType.SEARCH, 4, JsonCodec.toJson(new SearchRequest("键盘", 1, 100, "hnsw", 1.0))
                .getBytes(StandardCharsets.UTF_8));
        SearchResponse payload = JsonCodec.fromJson(poll().body(), SearchResponse.class);
        assertEquals(1, payload.hits().size(), "topK=1 应只返回一条");

        // 与直接调用服务层的结果比对，确认网关没有改变检索语义
        SearchParams params = new SearchParams(1, 100, 8);
        assertEquals(retrieval.search(IndexKind.HNSW, "键盘", params, 1.0).hits().get(0).docId(),
                payload.hits().get(0).id());
    }
}
