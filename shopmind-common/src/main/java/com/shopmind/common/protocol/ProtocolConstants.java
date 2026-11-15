package com.shopmind.common.protocol;

/**
 * 自研二进制协议的常量定义。
 *
 * <p>设计要点：定长头 + 变长体的紧凑帧结构，避免 JSON 全量传输带来的解析开销；
 * 头部只保留路由与校验必需字段，业务语义全部放在 body 中，便于后续扩展命令而不改协议版本。</p>
 */
public final class ProtocolConstants {

    /** 帧魔数，固定为 ASCII 'S''M'，用于识别非法数据流。 */
    public static final short MAGIC = 0x534D;

    /** 协议版本，升级协议结构时递增。 */
    public static final byte VERSION = 1;

    /** 头部长度：magic(2) + version(1) + cmd(1) + seq(4) + length(4)。 */
    public static final int HEADER_LENGTH = 12;

    /** body 最大长度上限，超过即视为异常帧，防止内存被单帧耗尽。 */
    public static final int MAX_BODY_LENGTH = 4 * 1024 * 1024;

    /** 心跳间隔（秒）。 */
    public static final int HEARTBEAT_INTERVAL_SECONDS = 30;

    /** 连续丢失心跳的容忍次数，超过即关闭连接。 */
    public static final int MAX_MISSED_HEARTBEATS = 3;

    private ProtocolConstants() {
    }
}
