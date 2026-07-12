package com.ironhack.backend.overcast.repo;

import com.ironhack.backend.overcast.domain.Scan;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

@Repository
public class ScanRepository {

    private final JdbcClient jdbc;

    public ScanRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Scan scan) {
        jdbc.sql("""
                INSERT INTO scan (id, source_cloud, filename, uploaded_at, currency,
                                  total_monthly_cost, total_monthly_waste, data_notes)
                VALUES (:id, :cloud, :filename, :uploadedAt, :currency, :cost, :waste, :dataNotes)
                """)
                .param("id", scan.id())
                .param("cloud", scan.sourceCloud())
                .param("filename", scan.filename())
                .param("uploadedAt", Timestamp.from(scan.uploadedAt()))
                .param("currency", scan.currency())
                .param("cost", scan.totalMonthlyCost())
                .param("waste", scan.totalMonthlyWaste())
                .param("dataNotes", scan.dataNotes())
                .update();
    }

    public Optional<Scan> findById(String id) {
        return jdbc.sql("SELECT * FROM scan WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new Scan(
                        rs.getString("id"),
                        rs.getString("source_cloud"),
                        rs.getString("filename"),
                        rs.getTimestamp("uploaded_at").toInstant(),
                        rs.getString("currency"),
                        rs.getBigDecimal("total_monthly_cost"),
                        rs.getBigDecimal("total_monthly_waste"),
                        rs.getString("data_notes")))
                .optional();
    }

    public boolean exists(String id) {
        return !jdbc.sql("SELECT 1 FROM scan WHERE id = :id").param("id", id)
                .query(Integer.class).list().isEmpty();
    }
}
