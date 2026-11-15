package com.shopmind.common.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * 自研二进制协议的编解码器。
 *
 * <p>解码流程：先校验魔数与版本（防止错误数据流被当成业务帧），再读命令与序列号，
 * 最后按 length 字段判断帧体是否到齐——未到齐时把读指针回退，等待下一次读取，
 * 因此天然支持半包与粘包，不需要额外的长度域拆包器。</p>
 */
public final class FrameCodec extends ByteToMessageDecoder {

    /**
     * 编码一帧。头部按协议顺序写入，body 直接拷贝，不做压缩。
     */
    public static ByteBuf encode(ByteBufAllocator allocator, Frame frame) {
        byte[] body = frame.body();
        ByteBuf buf = allocator.buffer(ProtocolConstants.HEADER_LENGTH + body.length);
        buf.writeShort(ProtocolConstants.MAGIC);
        buf.writeByte(ProtocolConstants.VERSION);
        buf.writeByte(frame.cmd().code());
        buf.writeInt(frame.seq());
        buf.writeInt(body.length);
        buf.writeBytes(body);
        return buf;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (true) {
            if (in.readableBytes() < ProtocolConstants.HEADER_LENGTH) {
                return;
            }
            in.markReaderIndex();

            short magic = in.readShort();
            if (magic != ProtocolConstants.MAGIC) {
                throw new ProtocolException(ErrorCode.INVALID_MAGIC,
                        "魔数校验失败，期望 0x534D，实际 0x" + Integer.toHexString(magic & 0xFFFF));
            }

            byte version = in.readByte();
            if (version != ProtocolConstants.VERSION) {
                throw new ProtocolException(ErrorCode.UNSUPPORTED_VERSION,
                        "协议版本不支持: " + version);
            }

            CmdType cmd = CmdType.of(in.readByte());
            int seq = in.readInt();
            int length = in.readInt();

            if (length < 0 || length > ProtocolConstants.MAX_BODY_LENGTH) {
                throw new ProtocolException(ErrorCode.BODY_TOO_LARGE, "帧体长度非法: " + length);
            }

            if (in.readableBytes() < length) {
                in.resetReaderIndex();
                return;
            }

            byte[] body = new byte[length];
            in.readBytes(body);
            out.add(new Frame(cmd, seq, body));
        }
    }
}
