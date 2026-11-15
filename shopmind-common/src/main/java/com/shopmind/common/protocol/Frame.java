package com.shopmind.common.protocol;

import java.util.Arrays;

/**
 * 协议帧。
 *
 * <p>魔数与版本由编解码器统一填充和校验，帧对象只承载业务可变的三个字段，
 * 避免上层代码误改协议头。</p>
 */
public record Frame(CmdType cmd, int seq, byte[] body) {

    public Frame {
        if (cmd == null) {
            throw new IllegalArgumentException("cmd 不能为空");
        }
        body = body == null ? new byte[0] : body;
    }

    public static Frame of(CmdType cmd, int seq, byte[] body) {
        return new Frame(cmd, seq, body);
    }

    public static Frame error(int seq, ErrorCode code, String detail) {
        String payload = "{\"code\":" + code.code() + ",\"message\":\"" + escape(detail) + "\"}";
        return new Frame(CmdType.ERROR, seq, longText(payload));
    }

    private static byte[] longText(String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Frame frame)) {
            return false;
        }
        return seq == frame.seq && cmd == frame.cmd && Arrays.equals(body, frame.body);
    }

    @Override
    public int hashCode() {
        int result = cmd.hashCode();
        result = 31 * result + seq;
        result = 31 * result + Arrays.hashCode(body);
        return result;
    }

    @Override
    public String toString() {
        return "Frame{cmd=" + cmd + ", seq=" + seq + ", bodyLen=" + body.length + "}";
    }
}
