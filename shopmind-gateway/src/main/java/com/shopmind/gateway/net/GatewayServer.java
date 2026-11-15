package com.shopmind.gateway.net;

import com.shopmind.common.protocol.FrameCodec;
import com.shopmind.service.retrieval.RetrievalService;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * 长连接网关服务端。
 *
 * <p>pipeline 只挂三样东西：自研编解码器、心跳超时检测、业务处理器。
 * 刻意不引入 HTTP 编解码——这条链路承载的是紧凑二进制检索协议，
 * 与面向页面的 HTTP 接口分工不同，这也是报告里"为什么不用 REST 做埋点通道"的答案。</p>
 */
public final class GatewayServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GatewayServer.class);

    private final int port;
    private final RetrievalService retrieval;
    private final String token;
    private final double limitCapacity;
    private final double limitRefillPerSecond;
    private final GatewayMetrics metrics = new GatewayMetrics();

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public GatewayServer(int port, RetrievalService retrieval, String token) {
        this(port, retrieval, token, 50.0, 50.0);
    }

    /**
     * @param limitCapacity          每连接令牌桶容量（突发上限）
     * @param limitRefillPerSecond   每连接令牌桶补充速率（长期速率上限）
     */
    public GatewayServer(int port, RetrievalService retrieval, String token,
                         double limitCapacity, double limitRefillPerSecond) {
        this.port = port;
        this.retrieval = retrieval;
        this.token = token;
        this.limitCapacity = limitCapacity;
        this.limitRefillPerSecond = limitRefillPerSecond;
    }

    public GatewayServer start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast("idle", new IdleStateHandler(90, 0, 0, TimeUnit.SECONDS))
                                .addLast("codec", new FrameCodec())
                                .addLast("handler", new GatewayHandler(
                                        retrieval, metrics, token, limitCapacity, limitRefillPerSecond));
                    }
                });

        serverChannel = bootstrap.bind(port).sync().channel();
        log.info("网关已启动，监听端口 {}（自研二进制协议）", port);
        return this;
    }

    public GatewayMetrics metrics() {
        return metrics;
    }

    public int port() {
        if (serverChannel != null && serverChannel.localAddress() != null) {
            return ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();
        }
        return port;
    }

    @Override
    public void close() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        log.info("网关已关闭");
    }
}
