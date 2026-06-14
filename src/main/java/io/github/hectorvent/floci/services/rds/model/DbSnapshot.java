package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * Represents an RDS DB snapshot (manual or automated).
 */
@RegisterForReflection
public class DbSnapshot {

    private String dbSnapshotIdentifier;
    private String dbInstanceIdentifier;
    private String engine;
    private String engineVersion;
    private String snapshotType;
    private String status;
    private int allocatedStorage;
    private String masterUsername;
    private int port;
    private String dbSnapshotArn;
    private Instant snapshotCreateTime;

    public DbSnapshot() {}

    public DbSnapshot(String dbSnapshotIdentifier, String dbInstanceIdentifier,
                      String engine, String engineVersion, String snapshotType,
                      String status, int allocatedStorage, String masterUsername,
                      int port, String dbSnapshotArn, Instant snapshotCreateTime) {
        this.dbSnapshotIdentifier = dbSnapshotIdentifier;
        this.dbInstanceIdentifier = dbInstanceIdentifier;
        this.engine = engine;
        this.engineVersion = engineVersion;
        this.snapshotType = snapshotType;
        this.status = status;
        this.allocatedStorage = allocatedStorage;
        this.masterUsername = masterUsername;
        this.port = port;
        this.dbSnapshotArn = dbSnapshotArn;
        this.snapshotCreateTime = snapshotCreateTime;
    }

    public String getDbSnapshotIdentifier() { return dbSnapshotIdentifier; }
    public void setDbSnapshotIdentifier(String dbSnapshotIdentifier) { this.dbSnapshotIdentifier = dbSnapshotIdentifier; }

    public String getDbInstanceIdentifier() { return dbInstanceIdentifier; }
    public void setDbInstanceIdentifier(String dbInstanceIdentifier) { this.dbInstanceIdentifier = dbInstanceIdentifier; }

    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }

    public String getEngineVersion() { return engineVersion; }
    public void setEngineVersion(String engineVersion) { this.engineVersion = engineVersion; }

    public String getSnapshotType() { return snapshotType; }
    public void setSnapshotType(String snapshotType) { this.snapshotType = snapshotType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getAllocatedStorage() { return allocatedStorage; }
    public void setAllocatedStorage(int allocatedStorage) { this.allocatedStorage = allocatedStorage; }

    public String getMasterUsername() { return masterUsername; }
    public void setMasterUsername(String masterUsername) { this.masterUsername = masterUsername; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getDbSnapshotArn() { return dbSnapshotArn; }
    public void setDbSnapshotArn(String dbSnapshotArn) { this.dbSnapshotArn = dbSnapshotArn; }

    public Instant getSnapshotCreateTime() { return snapshotCreateTime; }
    public void setSnapshotCreateTime(Instant snapshotCreateTime) { this.snapshotCreateTime = snapshotCreateTime; }
}
