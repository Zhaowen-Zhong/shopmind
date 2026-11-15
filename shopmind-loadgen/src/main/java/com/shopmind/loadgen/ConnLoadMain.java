package com.shopmind.loadgen;

import com.shopmind.common.json.JsonCodec;
import com.shopmind.common.protocol.CmdType;
import com.shopmind.common.protocol.Frame;
import com.shopmind.common.protocol.FrameCodec;
import com.shopmind.gateway.dto.HandshakeRequest;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * NFR-04 长连接稳态验收客户端。
 *
 * <p>一次性建立 5000 条 TCP 长连接并完成握手，之后每 30 秒批量发送心跳，
 * 每 60 秒采样一次"活跃连接数 + 堆内存 + 线程数"，持续 30 分钟。
 * 判定口径：连接不被动断开、堆内存不随时间单调上涨（无泄漏）、
 * 结束后主动关闭全部连接，服务端 activeConnections 应归零。</p>
 */
public final class ConnLoadMain {

    private ConnLoadMain() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = LoadGenMain.parseArgs(args);
        String host = options.getOrDefault("host", "127.0.0.1");
        int port = Integer.parseInt(options.getOrDefault("port", "9099"));
        String token = options.getOrDefault("token", "shopmind-demo-token");
        int connections = Integer.parseInt(options.getOrDefault("connections", "5000"));
        int durationSeconds = Integer.parseInt(options.getOrDefault("duration", "1800"));
        String out = options.getOrDefault("out", "docs/experiments/LT-03-connload.csv");

        EventLoopGroup group = new NioEventLoopGroup(Math.min(4, Runtime.getRuntime().availableProcessors()));
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        // 记录仍然活跃的连接，断线时从集合移除，便于采样发现被动断开
        Map<Integer, Channel> active = new ConcurrentHashMap<>();

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new FrameCodec());
                    }
                });

        long connectStart = System.currentTimeMillis();
        List<Channel> all = new ArrayList<>(connections);
        int handshakeFailures = 0;
        for (int i = 0; i < connections; i++) {
            try {
                Channel channel = bootstrap.connect(host, port).sync().channel();
                byte[] body = JsonCodec.toJson(new HandshakeRequest(token))
                        .getBytes(StandardCharsets.UTF_8);
                channel.writeAndFlush(FrameCodec.encode(channel.alloc(),
                        new Frame(CmdType.HANDSHAKE, i + 1, body)));
                all.add(channel);
                active.put(channel.hashCode(), channel);
                channel.closeFuture().addListener(future -> active.remove(channel.hashCode()));
            } catch (Exception e) {
                handshakeFailures++;
            }
            if ((i + 1) % 1000 == 0) {
                System.out.printf("已建立 %d/%d 条连接，失败 %d%n", i + 1, connections, handshakeFailures);
            }
        }
        System.out.printf("连接建立完成：%d 条，耗时 %d ms，失败 %d%n",
                all.size(), System.currentTimeMillis() - connectStart, handshakeFailures);

        // 每 30 秒对活跃连接批量发心跳
        scheduler.scheduleAtFixedRate(() -> {
            int seq = 1;
            for (Channel channel : active.values()) {
                if (channel.isActive()) {
                    channel.writeAndFlush(FrameCodec.encode(channel.alloc(),
                            new Frame(CmdType.HEARTBEAT, seq++, new byte[0])));
                }
            }
        }, 30, 30, TimeUnit.SECONDS);

        // 每 60 秒采样一次
        List<String> samples = new ArrayList<>();
        samples.add("elapsed_s,active_connections,heap_used_mb,heap_committed_mb,thread_count");
        for (int elapsed = 0; elapsed <= durationSeconds; elapsed += 60) {
            MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            int threadCount = ManagementFactory.getThreadMXBean().getThreadCount();
            String line = String.format("%d,%d,%d,%d,%d",
                    elapsed, active.size(),
                    heap.getUsed() / 1024 / 1024, heap.getCommitted() / 1024 / 1024, threadCount);
            samples.add(line);
            System.out.println(line);
            if (elapsed < durationSeconds) {
                Thread.sleep(60_000);
            }
        }

        System.out.printf("稳态结束：活跃连接 %d（建立时 %d），被动断开 %d%n",
                active.size(), all.size(), all.size() - active.size());

        for (Channel channel : all) {
            channel.close();
        }
        scheduler.shutdownNow();
        group.shutdownGracefully().await(10, TimeUnit.SECONDS);

        LoadGenMain.writeLines(out, samples);
        System.out.println("采样数据已写入：" + out);
    }
}
