package com.github.drakepork.taskbot;

import org.mariadb.jdbc.MariaDbPoolDataSource;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

public class DatabaseHook {
    private final DataSource dataSource;
    public DatabaseHook(Properties config) {
        Map<String, String> dbProp = new HashMap<>();
        dbProp.put("database.ip", null);
        dbProp.put("database.port", null);
        dbProp.put("database.name", null);
        dbProp.put("database.user", null);
        dbProp.put("database.password", null);

        List<String> missingKeys = new ArrayList<>();
        for (String key : dbProp.keySet()) {
            String value = config.getProperty(key);
            if (value == null || value.isEmpty()) {
                missingKeys.add(key);
            }
            dbProp.put(key, value);
        }
        if(!missingKeys.isEmpty()) {
            System.err.println("Missing or empty configuration for: " + String.join(", ", missingKeys) + ". Exiting...");
            System.exit(1);
        }

        MariaDbPoolDataSource dataSource = new MariaDbPoolDataSource();
        try {
            dataSource.setUrl("jdbc:mariadb://" + dbProp.get("database.ip") + ":" + dbProp.get("database.port") + "/" + dbProp.get("database.name") + "?user=" + dbProp.get("database.user") + "&password=" + dbProp.get("database.password"));
        } catch (SQLException e) {
            System.err.println("Failed to connect to the database! Exiting...");
            System.exit(1);
        }
        this.dataSource = dataSource;
    }
    public Connection getConnection() {
        try {
            return dataSource.getConnection();
        } catch (SQLException e) {
            System.err.println("Failed to connect to the database! Exiting...");
            System.exit(1);
        }
        return null;
    }
}
