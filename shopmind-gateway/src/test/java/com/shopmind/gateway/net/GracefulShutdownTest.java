package com.shopmind.gateway.net;

import com.shopmind.common.json.JsonCodec;
import com.shopmind.common.protocol.CmdType;
import com.shopmind.common.protocol.Frame;
import com.shopmind.common.protocol.FrameCodec;
import com.shopmind.gateway.dto.HandshakeRequest;
import com.shopmind.service.model.ProductDoc;
import com.shopmind.service.retrieval.RetrievalService;
import com.shopmind.service.store.KnowledgeBase;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LT-05 故障注入与优雅停机验证。
 *
 * <p>覆盖两件事：① 服务端优雅停机时，已建立的连接被逐个关闭、事件循环组终止，
 * 停机后原端口不再接受新连接；② 服务重启后，客户端按固定退避重试可以重新握手成功，
 * 模拟"进程崩溃 → 守护拉起 → 客户端自愈"的真实故障场景。</p>
 */
class GracefulShutdownTest {

    private static final String TOKEN = "shopmind-shutdown-token";

    private EventLoopGroup clientGroup;
    private final List<KnowledgeBase> knowledgeBases = new ArrayList<>();
    private final List<GatewayServer> servers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (GatewayServer server : servers) {
            server.close();
        }
        if (clientGroup != null) {
            clientGroup.shutdownGracefully();
        }
        for (KnowledgeBase knowledgeBase : knowledgeBases) {
            knowledgeBase.close();
        }
    }

    @Test
    void gracefulCloseShouldDisconnectClientsAndRejectNewConnections() throws Exception {
        GatewayServer server = newServer(0);
        int port = server.port();

        clientGroup = new NioEventLoopGroup(1);
        List<Channel> channels = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Channel channel = openConnection(port);
            handshake(channel);
            channels.add(channel);
        }
        awaitActive(server, 2, "停机前应有两条活跃连接");

        server.close();

        for (Channel channel : channels) {
            boolean closed = channel.closeFuture().await(5, TimeUnit.SECONDS);
            assertTrue(closed && !channel.isActive(), "优雅停机应关闭已建立的客户端连接");
        }
        awaitActive(server, 0, "停机后活跃连接计数应归零");

        // 停机后再连应当失败（连接被拒绝），而不是挂起
        boolean rejected = false;
        try {
            openConnection(port);
        } catch (Exception e) {
            rejected = true;
        }
        assertTrue(rejected, "停机后原端口不应再接受新连接");
    }

    @Test
    void clientShouldReconnectAfterServerRestart() throws Exception {
        GatewayServer first = newServer(0);
        int port = first.port();

        clientGroup = new NioEventLoopGroup(1);
        Channel channel = openConnection(port);
        handshake(channel);
        assertTrue(channel.isActive());

        // 模拟进程崩溃：直接关闭旧服务端
        first.close();
        channel.closeFuture().await(5, TimeUnit.SECONDS);
        assertFalse(channel.isActive(), "服务端崩溃后客户端连接应失效");

        // 模拟守护进程重新拉起：在同端口启动新服务端
        GatewayServer restarted = newServer(port);

        // 客户端退避重试，最多 10 次 × 200ms
        Channel reconnected = null;
        for (int attempt = 0; attempt < 10 && reconnected == null; attempt++) {
            Thread.sleep(200);
            try {
                Channel candidate = openConnection(port);
                handshake(candidate);
                reconnected = candidate;
            } catch (Exception ignored) {
                // 服务尚未就绪，继续退避重试
            }
        }

        assertTrue(reconnected != null && reconnected.isActive(), "客户端应能在服务重启后重连成功");
        awaitActive(restarted, 1, "重连后服务端应统计到一条新连接");
    }

    /** 轮询等待服务端活跃连接计数达到预期，消除客户端连接与服务端 channelActive 事件之间的时序竞争。 */
    private void awaitActive(GatewayServer server, int expected, String message) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (server.metrics().activeConnections() == expected) {
                return;
            }
            Thread.sleep(50);
        }
        assertTrue(server.metrics().activeConnections() == expected, message);
    }

    private GatewayServer newServer(int port) throws Exception {
        KnowledgeBase knowledgeBase = KnowledgeBase.memory();
        RetrievalService retrieval = new RetrievalService(knowledgeBase);
        retrieval.addDocument(new ProductDoc(1L, "蓝牙耳机", "主动降噪蓝牙耳机", "音频"));
        GatewayServer server = new GatewayServer(port, retrieval, TOKEN).start();
        knowledgeBases.add(knowledgeBase);
        servers.add(server);
        return server;
    }

    private Channel openConnection(int port) throws InterruptedException {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(clientGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new FrameCodec());
                    }
                });
        return bootstrap.connect("127.0.0.1", port).sync().channel();
    }

    private void handshake(Channel channel) {
        byte[] body = JsonCodec.toJson(new HandshakeRequest(TOKEN)).getBytes(StandardCharsets.UTF_8);
        channel.writeAndFlush(FrameCodec.encode(channel.alloc(), new Frame(CmdType.HANDSHAKE, 1, body)));
    }
}
