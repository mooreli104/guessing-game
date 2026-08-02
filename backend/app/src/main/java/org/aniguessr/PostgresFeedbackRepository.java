package org.aniguessr;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class PostgresFeedbackRepository implements FeedbackRepository {

    private final Db db;

    public PostgresFeedbackRepository(Db db) {
        this.db = db;
        db.createFeedbackTable();
    }

    @Override
    public void save(Feedback feedback) {
        String sql = "INSERT INTO feedback (kind, message, contact) VALUES (?, ?, ?)";
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, feedback.kind());
            ps.setString(2, feedback.message());
            ps.setString(3, feedback.contact());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Could not save feedback", e);
        }
    }
}
