package com.shopmind.service.store;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopmind.service.model.ProductDoc;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 内存映射顺序写前日志（Write-Ahead Log），纯 JDK 实现，无外部中间件。
 *
 * <p>热路径通过 {@link MappedByteBuffer} 把记录直接写入页缓存对应的映射内存，
 * 不发起 write 系统调用、不逐条 fsync；JSON 序列化在锁外完成，锁内只做一次
 * 内存拷贝，因此单次追加稳定在十几微秒量级。强制刷盘与 checkpoint 由后台
 * applier 按时间间隔批量完成，marker 的临时文件写在锁外，锁内只做原子改名。
 * 文件按 16 MiB 分段映射，写满一段后切换到下一段。</p>
 *
 * <p>持久化口径：checkpoint 之前的记录已确认落入 H2；崩溃恢复时只回放
 * checkpoint 之后的尾部记录（MERGE 幂等），未刷盘的最后一小段可能丢失，
 * 丢失窗口等于批量刷盘间隔，与异步提交语义一致。</p>
 */
public final class WriteAheadLog implements AutoCloseable {

    private static final String LOG_FILE = "wal.log";
    private static final String CHECKPOINT_FILE = "wal.checkpoint";
    private static final int CHUNK_BYTES = 16 * 1024 * 1024;

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Path directory;
    private final Path logPath;
    private final Path checkpointPath;
    private FileChannel channel;
    private MappedByteBuffer mapped;
    private long chunkStart;
    private volatile long writeOffset;
    private volatile long checkpointOffset;
    /** 只保护映射内存写入与分段切换，锁内禁止任何文件 I/O。 */
    private final Object writeLock = new Object();

    /**
     * 按数据文件路径派生 WAL 目录：数据文件 {@code ./data/shopmind} 对应 {@code ./data/shopmind-wal}。
     */
    public static WriteAheadLog at(String dataPath) {
        Path base = Path.of(dataPath);
        Path parent = base.toAbsolutePath().getParent();
        Path dir = (parent == null ? Path.of(".") : parent).resolve(base.getFileName() + "-wal");
        return new WriteAheadLog(dir);
    }

    public WriteAheadLog(Path directory) {
        this.directory = directory;
        this.logPath = directory.resolve(LOG_FILE);
        this.checkpointPath = directory.resolve(CHECKPOINT_FILE);
        try {
            Files.createDirectories(directory);
            this.channel = FileChannel.open(logPath,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            this.writeOffset = channel.size();
            this.checkpointOffset = readCheckpoint();
            mapChunk(writeOffset);
        } catch (IOException e) {
            throw new IllegalStateException("打开 WAL 失败: " + directory, e);
        }
    }

    /**
     * 顺序追加一条记录，返回追加后的字节偏移。
     * JSON 序列化在锁外完成，锁内只做映射内存拷贝，不做 fsync。
     */
    public long append(ProductDoc doc) {
        final byte[] payload;
        try {
            payload = (mapper.writeValueAsString(doc) + "\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("WAL 序列化失败: " + doc.id(), e);
        }
        synchronized (writeLock) {
            try {
                if (writeOffset + payload.length > chunkStart + CHUNK_BYTES) {
                    mapped.force();
                    unmap(mapped);
                    mapChunk(writeOffset);
                }
                mapped.put(payload);
                writeOffset += payload.length;
                return writeOffset;
            } catch (IOException e) {
                throw new IllegalStateException("WAL 追加失败: " + doc.id(), e);
            }
        }
    }

    /**
     * 强制把映射内存的脏页刷入磁盘，由后台 applier 批量调用。
     * 不加锁：fsync 可能耗时数毫秒，且 FileChannel/MappedByteBuffer 支持与写入并发。
     */
    public void force() {
        try {
            mapped.force();
        } catch (Exception e) {
            throw new IllegalStateException("WAL 刷盘失败", e);
        }
    }

    /**
     * 确认 safeOffset 之前的记录已持久化到 H2。临时 marker 在锁外写好，
     * 锁内只做原子改名并更新偏移，不做 fsync（调用方须先 {@link #force()}）。
     * 不做文件截断：压缩会改变文件偏移，导致 Pending 中缓存的旧偏移失效。
     */
    public void checkpoint(long safeOffset) {
        if (safeOffset <= checkpointOffset) {
            return;
        }
        if (safeOffset > writeOffset) {
            throw new IllegalArgumentException("checkpoint 偏移超出 WAL 长度: " + safeOffset);
        }
        try {
            Path tmp = directory.resolve(CHECKPOINT_FILE + ".tmp");
            Files.writeString(tmp, Long.toString(safeOffset),
                    java.nio.charset.StandardCharsets.UTF_8);
            synchronized (writeLock) {
                Files.move(tmp, checkpointPath,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                checkpointOffset = safeOffset;
            }
        } catch (IOException e) {
            throw new IllegalStateException("WAL checkpoint 失败", e);
        }
    }

    /**
     * 停机压缩（尽力而为）：仅在 applier 已终止、无生产者后调用。
     * 全部记录已 checkpoint 时尝试截断文件、偏移归零。
     * Windows 下映射内存的文件锁可能导致截断失败，此时仅记录、不抛异常——
     * checkpoint 标记已保证重启后不会重复回放，保留文件不影响正确性。
     */
    public void truncateApplied() {
        if (checkpointOffset <= 0 || checkpointOffset < writeOffset) {
            return;
        }
        synchronized (writeLock) {
            try {
                unmap(mapped);
                mapped = null;
                channel.truncate(0);
                writeOffset = 0;
                checkpointOffset = 0;
                Path tmp = directory.resolve(CHECKPOINT_FILE + ".tmp");
                Files.writeString(tmp, "0", java.nio.charset.StandardCharsets.UTF_8);
                Files.move(tmp, checkpointPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                mapChunk(writeOffset);
            } catch (IOException | RuntimeException e) {
                try {
                    if (mapped == null) {
                        mapChunk(writeOffset);
                    }
                } catch (IOException restoreError) {
                    // 留待 GC 回收映射，close 仍可关闭通道
                }
            }
        }
    }

    /**
     * 崩溃恢复：读取 checkpoint 之后的全部完整 JSON 行，返回待回放文档。
     * 容错口径：只解析以 '{' 开头的行；零填充、截断的半行或其它脏数据一律跳过，
     * 单条解析失败不阻断整体启动，避免损坏尾部把整个服务卡死。
     */
    public List<ProductDoc> recoverPending() {
        synchronized (writeLock) {
            try {
                long start = checkpointOffset;
                if (start >= writeOffset) {
                    return List.of();
                }
                ByteBuffer buffer = ByteBuffer.allocate((int) (writeOffset - start));
                long pos = start;
                while (buffer.hasRemaining()) {
                    int read = channel.read(buffer, pos);
                    if (read < 0) {
                        break;
                    }
                    pos += read;
                }
                String content = new String(buffer.array(), java.nio.charset.StandardCharsets.UTF_8);
                List<ProductDoc> docs = new ArrayList<>();
                for (String rawLine : content.split("\n")) {
                    String line = rawLine.trim();
                    if (line.isEmpty() || line.charAt(0) != '{') {
                        continue;
                    }
                    try {
                        docs.add(mapper.readValue(line, ProductDoc.class));
                    } catch (IOException parseError) {
                        // 跳过损坏/半行 JSON，继续回放其余记录
                    }
                }
                return docs;
            } catch (IOException e) {
                throw new IllegalStateException("WAL 恢复失败", e);
            }
        }
    }

    /** 全部记录均已确认落库时的全量 checkpoint（崩溃恢复回放后使用）。 */
    public void checkpointAll() {
        checkpoint(writeOffset);
    }

    public long pendingBytes() {
        return writeOffset - checkpointOffset;
    }

    @Override
    public void close() {
        synchronized (writeLock) {
            try {
                if (mapped != null) {
                    mapped.force();
                    unmap(mapped);
                    mapped = null;
                }
                if (channel != null && channel.isOpen()) {
                    channel.close();
                }
            } catch (IOException e) {
                throw new IllegalStateException("WAL 关闭失败", e);
            }
        }
    }

    /** 映射包含 offset 的分段，并把映射内位置定位到 offset。 */
    private void mapChunk(long offset) throws IOException {
        this.chunkStart = (offset / CHUNK_BYTES) * CHUNK_BYTES;
        this.mapped = channel.map(FileChannel.MapMode.READ_WRITE, chunkStart, CHUNK_BYTES);
        this.mapped.position((int) (offset - chunkStart));
    }

    private long readCheckpoint() {
        try {
            if (!Files.exists(checkpointPath)) {
                return 0;
            }
            String value = Files.readString(checkpointPath, java.nio.charset.StandardCharsets.UTF_8).trim();
            return value.isBlank() ? 0 : Long.parseLong(value);
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 主动解除直接缓冲映射，避免文件锁残留。通过 sun.misc.Unsafe.invokeCleaner
     * （jdk.unsupported 模块导出，JDK 21 可用），失败时降级为等待 GC 回收。
     */
    private static void unmap(ByteBuffer buffer) {
        if (buffer == null || !buffer.isDirect()) {
            return;
        }
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Object unsafe = unsafeClass.getField("theUnsafe").get(null);
            Method invokeCleaner = unsafeClass.getMethod("invokeCleaner", ByteBuffer.class);
            invokeCleaner.invoke(unsafe, buffer);
        } catch (Exception e) {
            System.gc();
        }
    }
}
