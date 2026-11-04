package dev.undertow.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undertow.reporting.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Low-volume control store. One durable DB lock serializes admission and ledger transitions. */
public final class ServiceDatabase {
    private static final SecureRandom RANDOM = new SecureRandom();

    @FunctionalInterface
    public interface Transaction<T> {
        T run(View view) throws Exception;
    }

    private final String url;
    private final SecretKeySpec key;

    public ServiceDatabase(String url, byte[] encryptionKey) throws SQLException {
        if (!url.startsWith("jdbc:h2:file:") && !url.startsWith("jdbc:h2:mem:")) {
            throw new IllegalArgumentException("Only embedded H2 is supported");
        }
        if (encryptionKey.length != 32) throw new IllegalArgumentException("AES-256 key required");
        this.url = url;
        key = new SecretKeySpec(encryptionKey.clone(), "AES");
        try (var connection = connect();
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS service_lock (id INT PRIMARY KEY)");
            statement.execute("MERGE INTO service_lock KEY(id) VALUES(1)");
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS service_records ("
                            + "kind VARCHAR(40), id VARCHAR(160), tenant VARCHAR(160), payload CLOB,"
                            + " PRIMARY KEY(kind,id))");
            statement.execute(
                    "CREATE INDEX IF NOT EXISTS records_owner ON service_records(kind,tenant)");
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, "sa", "");
    }

    public <T> T transaction(Transaction<T> action) throws Exception {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (var lock =
                    connection.prepareStatement(
                            "SELECT id FROM service_lock WHERE id=1 FOR UPDATE")) {
                try (var rows = lock.executeQuery()) {
                    if (!rows.next()) throw new SQLException("Missing transaction lock");
                }
                try {
                    T result = action.run(new View(connection));
                    connection.commit();
                    return result;
                } catch (Exception e) {
                    connection.rollback();
                    throw e;
                }
            }
        }
    }

    public final class View {
        private final Connection connection;

        private View(Connection connection) {
            this.connection = connection;
        }

        public ObjectNode get(String kind, String id, String tenant) throws Exception {
            try (var query =
                    connection.prepareStatement(
                            "SELECT payload FROM service_records WHERE kind=? AND id=? AND tenant=?")) {
                query.setString(1, kind);
                query.setString(2, id);
                query.setString(3, tenant);
                try (var rows = query.executeQuery()) {
                    return rows.next() ? decode(rows.getString(1), kind, id, tenant) : null;
                }
            }
        }

        /** Global enumeration is reserved for the scheduler/operator, never a customer route. */
        public List<ObjectNode> list(String kind, String tenant) throws Exception {
            String sql = "SELECT id,tenant,payload FROM service_records WHERE kind=?";
            if (tenant != null) sql += " AND tenant=?";
            sql += " ORDER BY id";
            try (var query = connection.prepareStatement(sql)) {
                query.setString(1, kind);
                if (tenant != null) query.setString(2, tenant);
                List<ObjectNode> values = new ArrayList<>();
                try (var rows = query.executeQuery()) {
                    while (rows.next())
                        values.add(
                                decode(
                                        rows.getString(3),
                                        kind,
                                        rows.getString(1),
                                        rows.getString(2)));
                }
                return values;
            }
        }

        public void insert(String kind, String id, String tenant, ObjectNode value)
                throws Exception {
            write(
                    "INSERT INTO service_records(kind,id,tenant,payload) VALUES(?,?,?,?)",
                    kind,
                    id,
                    tenant,
                    value);
        }

        public void put(String kind, String id, String tenant, ObjectNode value) throws Exception {
            // A globally unique ID cannot be reassigned to another tenant through an upsert.
            if (get(kind, id, tenant) == null) {
                insert(kind, id, tenant, value);
                return;
            }
            try (var update =
                    connection.prepareStatement(
                            "UPDATE service_records SET payload=? WHERE kind=? AND id=? AND tenant=?")) {
                update.setString(1, encode(value, kind, id, tenant));
                update.setString(2, kind);
                update.setString(3, id);
                update.setString(4, tenant);
                if (update.executeUpdate() != 1) throw new SQLException("Record changed");
            }
        }

        public void delete(String kind, String id, String tenant) throws SQLException {
            try (var query =
                    connection.prepareStatement(
                            "DELETE FROM service_records WHERE kind=? AND id=? AND tenant=?")) {
                query.setString(1, kind);
                query.setString(2, id);
                query.setString(3, tenant);
                query.executeUpdate();
            }
        }

        private void write(String sql, String kind, String id, String tenant, ObjectNode value)
                throws Exception {
            try (var update = connection.prepareStatement(sql)) {
                update.setString(1, kind);
                update.setString(2, id);
                update.setString(3, tenant);
                update.setString(4, encode(value, kind, id, tenant));
                update.executeUpdate();
            }
        }
    }

    private byte[] aad(String kind, String id, String tenant) {
        return (kind + "\n" + id + "\n" + tenant).getBytes(StandardCharsets.UTF_8);
    }

    private String encode(ObjectNode value, String kind, String id, String tenant)
            throws GeneralSecurityException, IOException {
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad(kind, id, tenant));
        byte[] ciphertext = cipher.doFinal(Json.stringify(value).getBytes(StandardCharsets.UTF_8));
        byte[] blob = new byte[nonce.length + ciphertext.length];
        System.arraycopy(nonce, 0, blob, 0, nonce.length);
        System.arraycopy(ciphertext, 0, blob, nonce.length, ciphertext.length);
        return Base64.getEncoder().encodeToString(blob);
    }

    private ObjectNode decode(String value, String kind, String id, String tenant)
            throws GeneralSecurityException, IOException {
        byte[] blob = Base64.getDecoder().decode(value);
        if (blob.length < 28) throw new IOException("Invalid encrypted record");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, blob, 0, 12));
        cipher.updateAAD(aad(kind, id, tenant));
        return (ObjectNode) Json.MAPPER.readTree(cipher.doFinal(blob, 12, blob.length - 12));
    }
}
