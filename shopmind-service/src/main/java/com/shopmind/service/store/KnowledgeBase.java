package com.shopmind.service.store;

import com.shopmind.service.model.ProductDoc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 商品知识库存储（H2 嵌入式数据库）。
 *
 * <p>选 H2 而不是 MySQL/Redis，是刻意的工程取舍：评审老师拿到压缩包后不需要装任何中间件，
 * 直接 java -jar 就能跑起来演示；对课程场景而言，"零安装可复现"比"用生产中间件"更有价值。</p>
 */
public final class KnowledgeBase implements AutoCloseable {

    private final String jdbcUrl;

    public static KnowledgeBase file(String path) {
        return new KnowledgeBase("jdbc:h2:file:" + path + ";AUTO_SERVER=TRUE");
    }

    /** 内存库，供单元测试使用。 */
    public static KnowledgeBase memory() {
        return new KnowledgeBase("jdbc:h2:mem:shopmind_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
    }

    public KnowledgeBase(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        init();
    }

    private void init() {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS product (
                        id BIGINT PRIMARY KEY,
                        title VARCHAR(512) NOT NULL,
                        description CLOB,
                        category VARCHAR(128),
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        } catch (SQLException e) {
            throw new IllegalStateException("初始化数据库失败", e);
        }
    }

    public void save(ProductDoc doc) {
        String sql = """
                MERGE INTO product (id, title, description, category) KEY(id) VALUES (?, ?, ?, ?)
                """;
        try (Connection conn = open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, doc.id());
            ps.setString(2, doc.title());
            ps.setString(3, doc.description() == null ? "" : doc.description());
            ps.setString(4, doc.category() == null ? "" : doc.category());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("保存文档失败: " + doc.id(), e);
        }
    }

    public boolean delete(long id) {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM product WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("删除文档失败: " + id, e);
        }
    }

    public Optional<ProductDoc> findById(long id) {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, title, description, category FROM product WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询文档失败: " + id, e);
        }
    }

    public List<ProductDoc> list(int offset, int limit) {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, title, description, category FROM product ORDER BY id LIMIT ? OFFSET ?")) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            return collect(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("分页查询失败", e);
        }
    }

    public List<ProductDoc> findAll() {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, title, description, category FROM product ORDER BY id")) {
            return collect(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("全量查询失败", e);
        }
    }

    public long count() {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM product");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("统计失败", e);
        }
    }

    @Override
    public void close() {
        // H2 以文件模式运行时，连接均在方法内关闭；此处保留接口以便未来切换连接池。
    }

    private List<ProductDoc> collect(PreparedStatement ps) throws SQLException {
        List<ProductDoc> docs = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                docs.add(map(rs));
            }
        }
        return docs;
    }

    private ProductDoc map(ResultSet rs) throws SQLException {
        return new ProductDoc(rs.getLong("id"), rs.getString("title"),
                rs.getString("description"), rs.getString("category"));
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, "sa", "");
    }
}
