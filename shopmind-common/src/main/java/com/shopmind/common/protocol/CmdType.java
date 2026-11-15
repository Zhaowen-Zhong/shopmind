package com.shopmind.common.protocol;

/**
 * 帧命令类型。
 *
 * <p>命令码按语义分段：0x0x 为连接生命周期，0x1x 为检索，0x2x 为流式推送，0x7F 为错误。
 * 保留空档便于后续扩展，且解析未知命令时返回明确错误码，不静默丢弃。</p>
 */
public enum CmdType {

    /** 客户端发起握手。 */
    HANDSHAKE((byte) 0x01),
    /** 服务端握手应答。 */
    HANDSHAKE_ACK((byte) 0x02),
    /** 心跳请求。 */
    HEARTBEAT((byte) 0x03),
    /** 心跳应答。 */
    HEARTBEAT_ACK((byte) 0x04),
    /** 检索请求。 */
    SEARCH((byte) 0x10),
    /** 检索结果。 */
    SEARCH_RESULT((byte) 0x11),
    /** 流式分片。 */
    STREAM((byte) 0x20),
    /** 流式结束。 */
    STREAM_END((byte) 0x21),
    /** 错误应答。 */
    ERROR((byte) 0x7F);

    private final byte code;

    CmdType(byte code) {
        this.code = code;
    }

    public byte code() {
        return code;
    }

    public static CmdType of(byte code) {
        for (CmdType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new ProtocolException(ErrorCode.UNKNOWN_COMMAND, "未知命令码: 0x" + Integer.toHexString(code));
    }
}
