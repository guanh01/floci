package io.github.hectorvent.floci.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.rds.RdsService;
import io.github.hectorvent.floci.services.sns.SnsService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SeedRawController RDS handling.
 * Verifies that POST /_admin/seed_raw/rds correctly routes to RdsService.
 */
class SeedRawControllerRdsTest {

    private SeedRawController controller;
    private RdsService rdsService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        rdsService = mock(RdsService.class);
        objectMapper = new ObjectMapper();
        controller = new SeedRawController(
                mock(DynamoDbService.class),
                mock(SnsService.class),
                mock(KmsService.class),
                mock(Ec2Service.class),
                rdsService,
                objectMapper);
    }

    @Test
    void seedRawRdsDbInstanceCallsSeedDbInstance() throws Exception {
        String payload = """
            {
                "ResourceType": "AWS::RDS::DBInstance",
                "Region": "us-east-1",
                "DBInstanceIdentifier": "cloudrail-demo-db",
                "DBInstanceStatus": "available",
                "Engine": "mysql",
                "EngineVersion": "8.0.35",
                "MasterUsername": "admin",
                "DBInstanceClass": "db.t3.micro",
                "AllocatedStorage": 20
            }
            """;
        JsonNode body = objectMapper.readTree(payload);

        Response response = controller.seedRaw("rds", body);

        assertEquals(200, response.getStatus());
        verify(rdsService).seedDbInstance("cloudrail-demo-db", "mysql", "8.0.35",
                "db.t3.micro", "admin", 20, "available");
    }

    @Test
    void seedRawRdsDbClusterCallsSeedDbCluster() throws Exception {
        String payload = """
            {
                "ResourceType": "AWS::RDS::DBCluster",
                "Region": "us-east-1",
                "DBClusterIdentifier": "my-cluster",
                "Status": "available",
                "Engine": "aurora-mysql",
                "EngineVersion": "8.0.mysql_aurora.3.04.1",
                "MasterUsername": "admin"
            }
            """;
        JsonNode body = objectMapper.readTree(payload);

        Response response = controller.seedRaw("rds", body);

        assertEquals(200, response.getStatus());
        verify(rdsService).seedDbCluster("my-cluster", "aurora-mysql",
                "8.0.mysql_aurora.3.04.1", "admin", "available");
    }

    @Test
    void seedRawRdsDbSnapshotCallsSeedDbSnapshot() throws Exception {
        String payload = """
            {
                "ResourceType": "AWS::RDS::DBSnapshot",
                "Region": "us-east-1",
                "DBSnapshotIdentifier": "my-snap",
                "DBInstanceIdentifier": "my-db",
                "Engine": "mysql",
                "EngineVersion": "8.0.35",
                "Status": "available",
                "AllocatedStorage": 50,
                "MasterUsername": "admin"
            }
            """;
        JsonNode body = objectMapper.readTree(payload);

        Response response = controller.seedRaw("rds", body);

        assertEquals(200, response.getStatus());
        verify(rdsService).seedDbSnapshot("my-snap", "my-db", "mysql",
                "8.0.35", "available", 50, "admin");
    }

    @Test
    void seedRawRdsMissingIdentifierReturns200WithZeroSeeded() throws Exception {
        String payload = """
            {
                "ResourceType": "AWS::RDS::DBInstance",
                "Region": "us-east-1"
            }
            """;
        JsonNode body = objectMapper.readTree(payload);

        Response response = controller.seedRaw("rds", body);

        assertEquals(200, response.getStatus());
        verifyNoInteractions(rdsService);
    }

    @Test
    void seedRawRdsUnsupportedResourceTypeReturns200WithZeroSeeded() throws Exception {
        String payload = """
            {
                "ResourceType": "AWS::RDS::DBProxy",
                "Region": "us-east-1"
            }
            """;
        JsonNode body = objectMapper.readTree(payload);

        Response response = controller.seedRaw("rds", body);

        assertEquals(200, response.getStatus());
        verifyNoInteractions(rdsService);
    }
}
