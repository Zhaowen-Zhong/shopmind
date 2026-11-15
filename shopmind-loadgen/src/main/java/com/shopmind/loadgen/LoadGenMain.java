package com.shopmind.loadgen;

import com.shopmind.common.json.JsonCodec;
import com.shopmind.common.protocol.CmdType;
import com.shopmind.common.protocol.Frame;
import com.shopmind.common.protocol.FrameCodec;
import com.shopmind.gateway.dto.HandshakeRequest;
import com.shopmind.gateway.dto.SearchRequest;
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

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 自研压测客户端。
 *
 * <p>不用 JMeter 的原因：JMeter 的 TCP sampler 无法按本项目自定义二进制协议拼帧，
 * 也无法拿到服务端返回的 seq 做请求级配对。自己写客户端反而能精确统计"发出到收到应答"的同一条请求延迟，
 * 这正是报告里 P50/P95/P99 的口径——不含排队、不含客户端线程调度。</p>
 */
public final class LoadGenMain {

    private static final class Config {
        String host = "127.0.0.1";
        int port = 9099;
        String token = "shopmind-demo-token";
        int connections = 10;
        int durationSeconds = 30;
        int ratePerConnection = 100;
        int topK = 10;
        String kind = "hnsw";
        double alpha = 0.8;
        String out = "docs/experiments/loadgen.csv";
        String query = "降噪蓝牙耳机";

        static Config parse(String[] args) {
            Config config = new Config();
            for (String arg : args) {
                String[] parts = arg.split("=", 2);
                if (parts.length != 2) {
                    continue;
                }
                switch (parts[0]) {
                    case "host" -> config.host = parts[1];
                    case "port" -> config.port = Integer.parseInt(parts[1]);
                    case "token" -> config.token = parts[1];
                    case "connections" -> config.connections = Integer.parseInt(parts[1]);
                    case "duration" -> config.durationSeconds = Integer.parseInt(parts[1]);
                    case "rate" -> config.ratePerConnection = Integer.parseInt(parts[1]);
                    case "topk" -> config.topK = Integer.parseInt(parts[1]);
                    case "kind" -> config.kind = parts[1];
                    case "alpha" -> config.alpha = Double.parseDouble(parts[1]);
                    case "out" -> config.out = parts[1];
                    case "query" -> config.query = parts[1];
                    default -> System.out.println("忽略未知参数: " + parts[0]);
                }
            }
            return config;
        }
    }

    /** 每条连接上的待应答请求：seq → 发送时刻（纳秒）。 */
    private static final class Pending {
        final Map<Integer, Long> sentAt = new ConcurrentHashMap<>();
        final AtomicInteger seq = new AtomicInteger();
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        System.out.printf("压测开始 host=%s port=%d connections=%d rate/conn=%d duration=%ds kind=%s topk=%d alpha=%.1f%n",
                config.host, config.port, config.connections, config.ratePerConnection,
                config.durationSeconds, config.kind, config.topK, config.alpha);

        EventLoopGroup group = new NioEventLoopGroup(Math.min(4, config.connections));
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(Math.min(8, config.connections));
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        AtomicLong errors = new AtomicLong();
        CountDownLatch handshakeLatch = new CountDownLatch(config.connections);
        List<Channel> channels = new ArrayList<>();
        List<Pending> pendings = new ArrayList<>();

        Path outPath = Path.of(config.out);
        if (outPath.getParent() != null) {
            Files.createDirectories(outPath.getParent());
        }
        BufferedWriter writer = Files.newBufferedWriter(outPath, StandardCharsets.UTF_8);
        writer.write("conn,seq,latency_micros\n");

        for (int i = 0; i < config.connections; i++) {
            Pending pending = new Pending();
            pendings.add(pending);
            final int connId = i;
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new FrameCodec());
                            ch.pipeline().addLast(new SimpleChannelInboundHandler<Frame>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, Frame frame) {
                                    if (frame.cmd() == CmdType.HANDSHAKE_ACK) {
                                        handshakeLatch.countDown();
                                        return;
                                    }
                                    if (frame.cmd() == CmdType.SEARCH_RESULT) {
                                        Long sent = pending.sentAt.remove(frame.seq());
                                        if (sent != null) {
                                            long latency = System.nanoTime() - sent;
                                            latencies.add(latency);
                                            try {
                                                synchronized (writer) {
                                                    writer.write(connId + "," + frame.seq() + "," + (latency / 1000) + "\n");
                                                }
                                            } catch (IOException e) {
                                                errors.incrementAndGet();
                                            }
                                        }
                                        return;
                                    }
                                    if (frame.cmd() == CmdType.ERROR) {
                                        errors.incrementAndGet();
                                    }
                                }
                            });
                        }
                    });
            Channel channel = bootstrap.connect(config.host, config.port).sync().channel();
            channels.add(channel);
            channel.writeAndFlush(FrameCodec.encode(channel.alloc(),
                    new Frame(CmdType.HANDSHAKE, 0,
                            JsonCodec.toJson(new HandshakeRequest(config.token)).getBytes(StandardCharsets.UTF_8))));
        }

        if (!handshakeLatch.await(20, TimeUnit.SECONDS)) {
            System.out.println("握手超时，压测终止");
            group.shutdownGracefully();
            return;
        }
        System.out.println("全部连接握手完成，开始施压");

        long intervalMicros = config.ratePerConnection <= 0 ? 1000 : 1_000_000L / config.ratePerConnection;
        for (int i = 0; i < channels.size(); i++) {
            Channel channel = channels.get(i);
            Pending pending = pendings.get(i);
            scheduler.scheduleAtFixedRate(() -> {
                int seq = pending.seq.incrementAndGet();
                pending.sentAt.put(seq, System.nanoTime());
                byte[] body = JsonCodec.toJson(new SearchRequest(
                        config.query, config.topK, 100, config.kind, config.alpha)).getBytes(StandardCharsets.UTF_8);
                channel.writeAndFlush(FrameCodec.encode(channel.alloc(), new Frame(CmdType.SEARCH, seq, body)));
            }, 0, intervalMicros, TimeUnit.MICROSECONDS);
        }

        Thread.sleep(config.durationSeconds * 1000L);
        scheduler.shutdownNow();
        Thread.sleep(2000);

        synchronized (writer) {
            writer.flush();
            writer.close();
        }
        for (Channel channel : channels) {
            channel.close();
        }
        group.shutdownGracefully().sync();

        List<Long> values = new ArrayList<>(latencies);
        if (values.isEmpty()) {
            System.out.println("未采集到有效延迟样本");
            return;
        }
        Collections.sort(values);
        long totalNanos = values.stream().mapToLong(Long::longValue).sum();
        double achievedRps = (double) values.size() / config.durationSeconds;
        System.out.println("conn,rate_per_conn,duration_s,requests,errors,avg_us,p50_us,p95_us,p99_us,rps");
        System.out.printf("%d,%d,%d,%d,%d,%.1f,%d,%d,%d,%.1f%n",
                config.connections, config.ratePerConnection, config.durationSeconds, values.size(),
                errors.get(), (double) totalNanos / values.size() / 1000.0,
                percentile(values, 50), percentile(values, 95), percentile(values, 99), achievedRps);

        Path summaryPath = Path.of(config.out.replace(".csv", "-summary.csv"));
        try (BufferedWriter summary = Files.newBufferedWriter(summaryPath, StandardCharsets.UTF_8)) {
            summary.write("conn,rate_per_conn,duration_s,requests,errors,avg_us,p50_us,p95_us,p99_us,rps\n");
            summary.write(String.format("%d,%d,%d,%d,%d,%.1f,%d,%d,%d,%.1f%n",
                    config.connections, config.ratePerConnection, config.durationSeconds, values.size(),
                    errors.get(), (double) totalNanos / values.size() / 1000.0,
                    percentile(values, 50), percentile(values, 95), percentile(values, 99), achievedRps));
        }
    }

    private static long percentile(List<Long> sorted, int p) {
        int index = (int) Math.ceil(sorted.size() * p / 100.0) - 1;
        index = Math.max(0, Math.min(sorted.size() - 1, index));
        return sorted.get(index) / 1000;
    }
}
