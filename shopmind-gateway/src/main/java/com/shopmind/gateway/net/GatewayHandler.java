package com.shopmind.gateway.net;

import com.shopmind.common.json.JsonCodec;
import com.shopmind.common.protocol.CmdType;
import com.shopmind.common.protocol.ErrorCode;
import com.shopmind.common.protocol.Frame;
import com.shopmind.common.protocol.FrameCodec;
import com.shopmind.common.protocol.ProtocolConstants;
import com.shopmind.common.protocol.ProtocolException;
import com.shopmind.gateway.dto.HandshakeAck;
import com.shopmind.gateway.dto.HandshakeRequest;
import com.shopmind.gateway.dto.HitDto;
import com.shopmind.gateway.dto.SearchRequest;
import com.shopmind.gateway.dto.SearchResponse;
import com.shopmind.index.IndexKind;
import com.shopmind.index.Neighbor;
import com.shopmind.index.SearchParams;
import com.shopmind.service.retrieval.RetrievalService;
import com.shopmind.service.retrieval.SearchOutcome;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 网关业务处理器：握手鉴权、心跳保活、限流、检索分发。
 *
 * <p>设计上把"连接状态"（是否已握手）放在 Channel 属性里，而不是放在处理器成员变量里，
 * 因为每个连接都复用一个处理器实例；把状态挂错地方是长连接服务最常见的串号 bug。</p>
 */
public final class GatewayHandler extends SimpleChannelInboundHandler<Frame> {

    private static final Logger log = LoggerFactory.getLogger(GatewayHandler.class);

    private static final io.netty.util.AttributeKey<Boolean> AUTHENTICATED =
            io.netty.util.AttributeKey.valueOf("shopmind.authenticated");
    private static final io.netty.util.AttributeKey<TokenBucket> BUCKET =
            io.netty.util.AttributeKey.valueOf("shopmind.bucket");

    private final RetrievalService retrieval;
    private final GatewayMetrics metrics;
    private final String expectedToken;
    private final double bucketCapacity;
    private final double bucketRefillPerSecond;

    public GatewayHandler(RetrievalService retrieval, GatewayMetrics metrics,
                          String expectedToken, double bucketCapacity, double bucketRefillPerSecond) {
        this.retrieval = retrieval;
        this.metrics = metrics;
        this.expectedToken = expectedToken;
        this.bucketCapacity = bucketCapacity;
        this.bucketRefillPerSecond = bucketRefillPerSecond;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ctx.channel().attr(BUCKET).set(new TokenBucket(bucketCapacity, bucketRefillPerSecond));
        metrics.connectionOpened();
        log.debug("连接建立 {}，当前活跃 {}", ctx.channel().remoteAddress(), metrics.activeConnections());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        metrics.connectionClosed();
        log.debug("连接关闭 {}，当前活跃 {}", ctx.channel().remoteAddress(), metrics.activeConnections());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Frame frame) {
        switch (frame.cmd()) {
            case HANDSHAKE -> handleHandshake(ctx, frame);
            case HEARTBEAT -> reply(ctx, new Frame(CmdType.HEARTBEAT_ACK, frame.seq(), new byte[0]));
            case SEARCH -> handleSearch(ctx, frame);
            default -> reply(ctx, Frame.error(frame.seq(), ErrorCode.UNKNOWN_COMMAND,
                    "该命令不允许由客户端发起: " + frame.cmd()));
        }
    }

    private void handleHandshake(ChannelHandlerContext ctx, Frame frame) {
        HandshakeRequest request;
        try {
            request = JsonCodec.fromJson(frame.body(), HandshakeRequest.class);
        } catch (IllegalArgumentException e) {
            metrics.unauthorized();
            reply(ctx, Frame.error(frame.seq(), ErrorCode.MALFORMED_PAYLOAD, e.getMessage()));
            ctx.close();
            return;
        }
        if (request.token() == null || !expectedToken.equals(request.token())) {
            metrics.unauthorized();
            reply(ctx, Frame.error(frame.seq(), ErrorCode.UNAUTHORIZED, "令牌校验失败"));
            ctx.close();
            return;
        }
        ctx.channel().attr(AUTHENTICATED).set(Boolean.TRUE);
        HandshakeAck ack = new HandshakeAck(UUID.randomUUID().toString(),
                ProtocolConstants.HEARTBEAT_INTERVAL_SECONDS, System.currentTimeMillis());
        reply(ctx, new Frame(CmdType.HANDSHAKE_ACK, frame.seq(),
                JsonCodec.toJson(ack).getBytes(StandardCharsets.UTF_8)));
    }

    private void handleSearch(ChannelHandlerContext ctx, Frame frame) {
        if (!Boolean.TRUE.equals(ctx.channel().attr(AUTHENTICATED).get())) {
            metrics.unauthorized();
            reply(ctx, Frame.error(frame.seq(), ErrorCode.UNAUTHORIZED, "请先完成握手"));
            return;
        }
        TokenBucket bucket = ctx.channel().attr(BUCKET).get();
        if (bucket != null && !bucket.tryAcquire()) {
            metrics.rejected();
            reply(ctx, Frame.error(frame.seq(), ErrorCode.RATE_LIMITED, "请求过于频繁，请稍后重试"));
            return;
        }

        try {
            SearchRequest request = JsonCodec.fromJson(frame.body(), SearchRequest.class);
            if (request.query() == null || request.query().isBlank()) {
                reply(ctx, Frame.error(frame.seq(), ErrorCode.MALFORMED_PAYLOAD, "query 不能为空"));
                return;
            }
            IndexKind kind = parseKind(request.kind());
            SearchParams params = new SearchParams(request.topK(), request.efSearch(), 8);
            SearchOutcome outcome = retrieval.search(kind, request.query(), params, request.alpha());

            List<HitDto> hits = new ArrayList<>(outcome.hits().size());
            for (Neighbor neighbor : outcome.hits()) {
                hits.add(new HitDto(neighbor.docId(), retrieval.titleOf(neighbor.docId()), neighbor.score()));
            }
            SearchResponse response = new SearchResponse(kind.label(), hits,
                    Math.round(outcome.latencyMicros() * 100) / 100.0, frame.seq());
            metrics.searchHandled();
            reply(ctx, new Frame(CmdType.SEARCH_RESULT, frame.seq(),
                    JsonCodec.toJson(response).getBytes(StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException e) {
            reply(ctx, Frame.error(frame.seq(), ErrorCode.MALFORMED_PAYLOAD, e.getMessage()));
        } catch (RuntimeException e) {
            log.error("检索处理失败 seq={}", frame.seq(), e);
            reply(ctx, Frame.error(frame.seq(), ErrorCode.INTERNAL_ERROR, "服务内部错误"));
        }
    }

    private IndexKind parseKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return IndexKind.HNSW;
        }
        return switch (kind.toLowerCase()) {
            case "brute-force", "brute", "bruteforce" -> IndexKind.BRUTE_FORCE;
            case "ivf" -> IndexKind.IVF;
            case "hnsw" -> IndexKind.HNSW;
            default -> throw new IllegalArgumentException("未知索引类型: " + kind);
        };
    }

    private void reply(ChannelHandlerContext ctx, Frame frame) {
        if (!ctx.channel().isActive()) {
            return;
        }
        ctx.writeAndFlush(FrameCodec.encode(ctx.alloc(), frame));
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            log.info("连接 {} 心跳超时，主动关闭", ctx.channel().remoteAddress());
            ctx.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof ProtocolException protocolException) {
            log.warn("协议错误，关闭连接 {}：{}", ctx.channel().remoteAddress(), protocolException.getMessage());
            reply(ctx, Frame.error(0, protocolException.errorCode(), protocolException.getMessage()));
        } else {
            log.error("连接异常 {}", ctx.channel().remoteAddress(), cause);
        }
        ctx.close();
    }
}
